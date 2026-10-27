package br.com.scriptping;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import br.com.scriptping.collector.proto.*;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class CollectorClientTests {

    @Test
    void databaseFailureRetriesOffCallbackAndAckFollowsSuccessfulCommit() throws Exception {
        var calls = new AtomicInteger();
        var ack = new CountDownLatch(1);
        var failed = new CountDownLatch(1);
        var store = mock(SampleStore.class);
        doAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                failed.countDown();
                throw new IllegalStateException("simulated database outage");
            }
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
                                if (c.hasConfig()) response.onNext(
                                    Batch.newBuilder()
                                        .setProtocolVersion(1)
                                        .setCollectorId("test")
                                        .setBatchId(1)
                                        .build()
                                );
                                if (c.hasAck()) ack.countDown();
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
            assertThat(failed.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(ack.getCount()).isEqualTo(1);
            assertThat(ack.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(calls.get()).isEqualTo(2);
        } finally {
            client.close();
            server.shutdownNow();
            server.awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
