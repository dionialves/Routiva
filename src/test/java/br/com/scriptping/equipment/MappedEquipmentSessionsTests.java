package br.com.scriptping.equipment;

import br.com.scriptping.zabbix.Host;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MappedEquipmentSessionsTests {
    @Test void expiredSessionCanBeRecreatedAndShutdownRejectsReads() throws Exception {
        var host = new Host("a", "A", "192.0.2.1", List.of());
        var credentials = new EquipmentProvider.Connection("reader", "secret", 443, true);
        var now = new java.util.concurrent.atomic.AtomicLong();
        var opens = new AtomicInteger();
        var closes = new AtomicInteger();
        var pool = new MappedEquipmentSessions<AutoCloseable>((h, c) -> {
            opens.incrementAndGet();
            return () -> { closes.incrementAndGet(); };
        }, now::get, ex -> ex instanceof IOException);
        try (pool) {
            pool.read(host, credentials, session -> "first");
            now.set(MappedEquipmentSessions.IDLE_MS);
            pool.expireIdle();
            assertThat(closes.get()).isEqualTo(1);
            assertThat(pool.<String>read(host, credentials, session -> "second")).isEqualTo("second");
            assertThat(opens.get()).isEqualTo(2);
        }
        assertThat(closes.get()).isEqualTo(2);
        assertThatThrownBy(() -> pool.read(host, credentials, session -> "closed"))
            .isInstanceOf(IOException.class).hasMessage("Session manager closed");
        assertThat(opens.get()).isEqualTo(2);
    }

    @Test void tenConcurrentInterfacesShareOneSessionWhileAnotherHostRemainsIndependent() throws Exception {
        var a = new Host("a", "A", "192.0.2.1", List.of());
        var b = new Host("b", "B", "192.0.2.2", List.of());
        var credentials = new EquipmentProvider.Connection("reader", "secret", 443, true);
        var transport = mock(AutoCloseable.class);
        var opens = new AtomicInteger();
        var active = new AtomicInteger();
        var peak = new AtomicInteger();
        var entered = new CountDownLatch(1);
        var ready = new CountDownLatch(10);
        var release = new CountDownLatch(1);
        try (var pool = new MappedEquipmentSessions<AutoCloseable>((host, c) -> {
            if (host.id().equals("a")) opens.incrementAndGet();
            return transport;
        }, ex -> ex instanceof IOException);
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var readers = new java.util.ArrayList<Future<Integer>>();
            for (int i = 0; i < 10; i++) {
                int port = i;
                readers.add(executor.submit(() -> {
                    ready.countDown();
                    return pool.read(a, credentials, session -> {
                        assertThat(session).isSameAs(transport);
                        peak.accumulateAndGet(active.incrementAndGet(), Math::max);
                        entered.countDown();
                        try {
                            if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("test timeout");
                            return port;
                        } finally { active.decrementAndGet(); }
                    });
                }));
            }
            try {
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(executor.submit(() -> pool.read(b, credentials, ignored -> "B"))
                    .get(2, TimeUnit.SECONDS)).isEqualTo("B");
                assertThat(opens.get()).isEqualTo(1);
            } finally { release.countDown(); }
            for (int i = 0; i < 10; i++) assertThat(readers.get(i).get(5, TimeUnit.SECONDS)).isEqualTo(i);
            assertThat(opens.get()).isEqualTo(1);
            assertThat(peak.get()).isEqualTo(1);
        }
    }
}
