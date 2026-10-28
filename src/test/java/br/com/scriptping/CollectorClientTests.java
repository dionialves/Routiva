package br.com.scriptping;

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
        var store = mock(SampleStore.class);
        doAnswer(invocation -> {
            writerThreads.add(Thread.currentThread());
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("simulated database outage");
            }
            retryEntered.countDown();
            if (!allowCommit.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Test did not release commit");
            successfulWrites.incrementAndGet();
            return null;
        })
            .when(store)
            .saveBatch(any());
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
            verify(store, atLeast(2)).saveBatch(argThat(batch ->
                batch.getCollectorId().equals("test") && batch.getBatchId() == 1));
        } finally {
            allowCommit.countDown();
            client.close();
            server.shutdownNow();
            server.awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
