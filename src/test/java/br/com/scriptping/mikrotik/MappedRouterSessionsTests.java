package br.com.scriptping.mikrotik;

import br.com.scriptping.equipment.EquipmentProvider.Connection;
import br.com.scriptping.zabbix.Host;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MappedRouterSessionsTests {
    private final Host a = new Host("a", "A", "127.0.0.1", List.of());
    private final Host b = new Host("b", "B", "127.0.0.2", List.of());
    private final Connection credentials = new Connection("reader", "test-only", 8728, false);

    @Test void sharesSessionAcrossInterfacesAndReadersButSeparatesEquipment() throws Exception {
        var clock = new AtomicLong();
        var factory = mock(MappedRouterSessions.Factory.class);
        var apiA = mock(RouterOsConnection.class);
        var apiB = mock(RouterOsConnection.class);
        when(factory.open(a, credentials)).thenReturn(apiA);
        when(factory.open(b, credentials)).thenReturn(apiB);
        try (var pool = new MappedRouterSessions(factory, clock::get)) {
            assertThat(pool.<RouterOsConnection>read(a, credentials, api -> api)).isSameAs(apiA);
            clock.set(10000);
            assertThat(pool.<RouterOsConnection>read(a, credentials, api -> api)).isSameAs(apiA);
            assertThat(pool.<RouterOsConnection>read(b, credentials, api -> api)).isSameAs(apiB);
            verify(factory, times(1)).open(a, credentials);
            verify(apiA, never()).close();
        }
        verify(apiA).close();
        verify(apiB).close();
    }

    @Test void idleExpiryKeepsRecentlyUsedSessionsAndClosesAbandonedOnes() throws Exception {
        var clock = new AtomicLong();
        var api = mock(RouterOsConnection.class);
        var opens = new AtomicInteger();
        try (var pool = new MappedRouterSessions((h, c) -> { opens.incrementAndGet(); return api; }, clock::get)) {
            pool.read(a, credentials, ignored -> true);
            clock.set(40000);
            pool.read(a, credentials, ignored -> true);
            clock.set(50000);
            pool.expireIdle();
            verify(api, never()).close();
            clock.set(85000);
            pool.expireIdle();
            verify(api).close();
            pool.read(a, credentials, ignored -> true);
            assertThat(opens.get()).isEqualTo(2);
        }
    }

    @Test void brokenReusedSocketReconnectsOnceAndFailedReplacementIsEvicted() throws Exception {
        var first = mock(RouterOsConnection.class);
        var replacement = mock(RouterOsConnection.class);
        var factory = mock(MappedRouterSessions.Factory.class);
        when(factory.open(a, credentials)).thenReturn(first, replacement);
        try (var pool = new MappedRouterSessions(factory, () -> 0)) {
            pool.read(a, credentials, api -> true);
            assertThat(pool.<RouterOsConnection>read(a, credentials, api -> {
                if (api == first) throw new IOException("closed");
                return api;
            })).isSameAs(replacement);
            verify(first).close();
            assertThatThrownBy(() -> pool.read(a, credentials, api -> { throw new IOException("offline"); }))
                .isInstanceOf(IOException.class);
            verify(factory, times(3)).open(a, credentials);
        }
    }

    @Test void changedCredentialsOrAddressReplaceSessionAndRejectedCommandsAreNotRetried() throws Exception {
        var factory = mock(MappedRouterSessions.Factory.class);
        var api = mock(RouterOsConnection.class);
        when(factory.open(any(), any())).thenReturn(api);
        try (var pool = new MappedRouterSessions(factory, () -> 0)) {
            pool.read(a, credentials, ignored -> true);
            var updated = new Connection("reader", "changed", 8729, true);
            pool.read(a, updated, ignored -> true);
            var moved = new Host("a", "A", "127.0.0.3", List.of());
            pool.read(moved, updated, ignored -> true);
            assertThatThrownBy(() -> pool.read(moved, updated, ignored -> {
                throw new RouterOsConnection.CommandRejectedException();
            })).isInstanceOf(RouterOsConnection.CommandRejectedException.class);
            verify(factory, times(3)).open(any(), any());
            verify(api, times(3)).close();
        }
    }

    @Test void concurrentReadersNeverInterleaveCommandsOnSameEquipment() throws Exception {
        var api = mock(RouterOsConnection.class);
        var active = new AtomicInteger();
        var peak = new AtomicInteger();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = new MappedRouterSessions((h, c) -> api, () -> 0);
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> pool.read(a, credentials, ignored -> {
                peak.accumulateAndGet(active.incrementAndGet(), Math::max);
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("test timeout"); }
                catch (InterruptedException ex) { throw new IOException(ex); }
                active.decrementAndGet(); return true;
            }));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> pool.read(a, credentials, ignored -> {
                peak.accumulateAndGet(active.incrementAndGet(), Math::max);
                active.decrementAndGet(); return true;
            }));
            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(second.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(peak.get()).isEqualTo(1);
        }
    }

    @Test void shutdownRefusesNewConnections() throws Exception {
        var factory = mock(MappedRouterSessions.Factory.class);
        var pool = new MappedRouterSessions(factory, () -> 0);
        pool.close();
        assertThatThrownBy(() -> pool.read(a, credentials, ignored -> true)).isInstanceOf(IOException.class);
        verifyNoInteractions(factory);
    }
}
