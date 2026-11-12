package br.com.scriptping;

import br.com.scriptping.ingestion.SampleIngestionService;

import br.com.scriptping.ingestion.CollectorClient;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import br.com.scriptping.collector.proto.*;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

class CollectorClientTests {
    @org.junit.jupiter.api.Test
    void clientProbesCoexistWithInventoryAndAreRemovedWhenModalSessionStops() {
        var client = new CollectorClient(mock(SampleIngestionService.class), "unused", "", "", "", "", false, 1000, 1500);
        var sessions = new br.com.scriptping.ingestion.ClientPingSessions();
        client.setClientPings(sessions);
        var sent = new java.util.concurrent.atomic.AtomicReference<Command>();
        ReflectionTestUtils.setField(client, "commands", new StreamObserver<Command>() {
            public void onNext(Command command) { sent.set(command); }
            public void onError(Throwable error) {}
            public void onCompleted() {}
        });
        ReflectionTestUtils.setField(client, "connected", true);
        var host = new br.com.scriptping.zabbix.Host("1", "AP", "192.0.2.1", java.util.List.of());
        var session = sessions.start("1", "owner", "192.0.2.15");
        client.synchronize(java.util.List.of(host));
        assertThat(sent.get().getConfig().getHostsList()).extracting(HostConfig::getHostId)
            .containsExactlyInAnyOrder("1", session.id());
        sessions.stop(session.id(), "1", "owner");
        client.synchronize(java.util.List.of(host));
        assertThat(sent.get().getConfig().getHostsList()).extracting(HostConfig::getHostId).containsExactly("1");
    }


    @org.junit.jupiter.api.Test
    void acknowledgedHostsAreRemovedFromCollectorConfigurationAndCanReturn() {
        var client = new CollectorClient(mock(SampleIngestionService.class), "unused", "", "", "", "", false, 1000, 1500);
        var active = new br.com.scriptping.zabbix.Host("1", "Host", "127.0.0.1", java.util.List.of(), false);
        var acknowledged = new br.com.scriptping.zabbix.Host("1", "Host", "127.0.0.1", java.util.List.of(), true);
        client.synchronize(java.util.List.of(active));
        assertThat((java.util.List<?>) ReflectionTestUtils.getField(client, "hosts")).hasSize(1);
        client.synchronize(java.util.List.of(acknowledged));
        assertThat((java.util.List<?>) ReflectionTestUtils.getField(client, "hosts")).isEmpty();
        client.synchronize(java.util.List.of(active));
        assertThat((java.util.List<?>) ReflectionTestUtils.getField(client, "hosts")).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void databaseFailureRetriesOffCallbackAndAckFollowsSuccessfulCommit(boolean reconnect) throws Exception {
        var calls = new AtomicInteger();
        var configurations = new CountDownLatch(2);
        var ack = new CountDownLatch(1);
        var retryEntered = new CountDownLatch(1);
        var allowCommit = new CountDownLatch(1);
        var successfulWrites = new AtomicInteger();
        var writesAtAck = new AtomicInteger();
        var callbackThreads = ConcurrentHashMap.<Thread>newKeySet();
        var writerThreads = ConcurrentHashMap.<Thread>newKeySet();
        var store = mock(SampleIngestionService.class);
        doAnswer(invocation -> {
            writerThreads.add(Thread.currentThread());
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("simulated database outage");
            }
            retryEntered.countDown();
            if (!allowCommit.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Test did not release commit");
            successfulWrites.incrementAndGet();
            return new SampleIngestionService.WriteResult();
        })
            .when(store)
            .saveBatches(any());
        var server = NettyServerBuilder.forPort(0)
            .addService(
                new CollectorGrpc.CollectorImplBase() {
                    @Override
                    public StreamObserver<Command> connect(StreamObserver<Batch> response) {
                        return new StreamObserver<>() {
                            public void onNext(Command c) {
                                callbackThreads.add(Thread.currentThread());
                                if (c.hasConfig()) {
                                    configurations.countDown();
                                    response.onNext(
                                    Batch.newBuilder()
                                        .setProtocolVersion(1)
                                        .setCollectorId("test")
                                        .setBatchId(1)
                                        .build()
                                    );
                                }
                                if (c.hasAck()) {
                                    writesAtAck.set(successfulWrites.get());
                                    ack.countDown();
                                }
                            }

                            public void onError(Throwable t) {}

                            public void onCompleted() {
                                response.onCompleted();
                            }
                        };
                    }

                    @Override
                    public void health(
                        HealthRequest request,
                        StreamObserver<HealthReply> response
                    ) {
                        response.onNext(HealthReply.newBuilder().setProtocolVersion(1).build());
                        response.onCompleted();
                    }
                }
            )
            .build()
            .start();
        var channel = NettyChannelBuilder.forAddress("127.0.0.1", server.getPort())
            .usePlaintext()
            .build();
        var client = new CollectorClient(store, "unused", "", "", "", "", true, 1000, 1500);
        ReflectionTestUtils.setField(client, "channel", channel);
        try {
            client.start();
            assertThat(retryEntered.await(15, TimeUnit.SECONDS)).isTrue();
            // Hold the successful write open: ACK must not race ahead of persistence.
            assertThat(ack.getCount()).isEqualTo(1);
            assertThat(successfulWrites.get()).isZero();
            if (reconnect) {
                // Force a new stream while the old delivery is still being persisted.
                synchronized (client) {
                    long generation = (long) ReflectionTestUtils.getField(client, "generation");
                    ReflectionTestUtils.invokeMethod(client, "disconnect", generation, "simulated reconnect");
                    ReflectionTestUtils.invokeMethod(client, "connect");
                }
                assertThat(configurations.await(15, TimeUnit.SECONDS)).isTrue();
            }
            allowCommit.countDown();
            assertThat(ack.await(15, TimeUnit.SECONDS)).isTrue();
            assertThat(writesAtAck.get()).isPositive();
            // Health timeouts/reconnects can legitimately redeliver the same batch.
            assertThat(calls.get()).isGreaterThanOrEqualTo(reconnect ? 3 : 2);
            assertThat(writerThreads).hasSize(1).doesNotContainAnyElementsOf(callbackThreads);
            verify(store, atLeast(2)).saveBatches(argThat(batches ->
                batches.stream().allMatch(batch -> batch.getCollectorId().equals("test") && batch.getBatchId() == 1)));
        } finally {
            allowCommit.countDown();
            client.close();
            server.shutdownNow();
            server.awaitTermination(5, TimeUnit.SECONDS);
        }
    }
    @ParameterizedTest
    @ValueSource(ints = {0, 3000})
    void groupsSeveralDeliveriesAndAcknowledgesEveryIdentityOnlyAfterCommit(int resultCount) throws Exception {
        var store=mock(SampleIngestionService.class);
        var entered=new CountDownLatch(1);
        var commit=new CountDownLatch(1);
        var acknowledged=new CountDownLatch(8);
        var captured=new java.util.concurrent.atomic.AtomicReference<java.util.List<Batch>>();
        var groupSizes=new java.util.concurrent.CopyOnWriteArrayList<Integer>();
        var advertisedWindow=new java.util.concurrent.atomic.AtomicReference<String>();
        var ackIds=new java.util.concurrent.CopyOnWriteArrayList<Long>();
        doAnswer(call->{
            java.util.List<Batch> group=call.getArgument(0);
            captured.compareAndSet(null,java.util.List.copyOf(group));
            groupSizes.add(group.size());
            assertThat(group.stream().mapToInt(Batch::getResultsCount).sum()).isLessThanOrEqualTo(5000);
            entered.countDown();
            if (!commit.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("commit blocked");
            return new SampleIngestionService.WriteResult();
        }).when(store).saveBatches(any());
        var server=NettyServerBuilder.forPort(0).intercept(new io.grpc.ServerInterceptor(){
            public <ReqT,RespT> io.grpc.ServerCall.Listener<ReqT> interceptCall(
                io.grpc.ServerCall<ReqT,RespT> call,io.grpc.Metadata headers,io.grpc.ServerCallHandler<ReqT,RespT> next){
                if(call.getMethodDescriptor().getFullMethodName().endsWith("/Connect"))
                    advertisedWindow.set(headers.get(io.grpc.Metadata.Key.of("x-routiva-window",io.grpc.Metadata.ASCII_STRING_MARSHALLER)));
                return next.startCall(call,headers);
            }
        }).addService(new CollectorGrpc.CollectorImplBase(){
            @Override public StreamObserver<Command> connect(StreamObserver<Batch> response){
                return new StreamObserver<>() {
                    public void onNext(Command command){
                        if(command.hasConfig()) for(int i=1;i<=8;i++) response.onNext(
                            Batch.newBuilder().setCollectorId("window").setBatchId(i).setProtocolVersion(1)
                                .addAllResults(java.util.Collections.nCopies(resultCount,Result.getDefaultInstance())).build());
                        if(command.hasAck()){ackIds.add(command.getAck().getBatchId());acknowledged.countDown();}
                    }
                    public void onError(Throwable error){}
                    public void onCompleted(){response.onCompleted();}
                };
            }
            @Override public void health(HealthRequest request,StreamObserver<HealthReply> response){
                response.onNext(HealthReply.getDefaultInstance());response.onCompleted();
            }
        }).build().start();
        var channel=NettyChannelBuilder.forAddress("127.0.0.1",server.getPort()).usePlaintext().build();
        var client=new CollectorClient(store,"unused","","","","",true,1000,1500);
        ReflectionTestUtils.setField(client,"channel",channel);
        ReflectionTestUtils.setField(client,"groupWaitMs",300L);
        try {
            client.start();
            assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            assertThat(advertisedWindow.get()).isEqualTo("16");
            assertThat(captured.get()).hasSize(resultCount==0?8:1);
            assertThat(ackIds).isEmpty();
            commit.countDown();
            assertThat(acknowledged.await(10,TimeUnit.SECONDS)).isTrue();
            assertThat(ackIds).containsExactly(1L,2L,3L,4L,5L,6L,7L,8L);
            verify(store,times(resultCount==0?1:8)).saveBatches(any());
            assertThat(groupSizes.stream().mapToInt(Integer::intValue).sum()).isEqualTo(8);
        } finally {
            commit.countDown();client.close();server.shutdownNow();server.awaitTermination(5,TimeUnit.SECONDS);
        }
    }

}
