package br.com.scriptping;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class PacketLossCacheTests {

    private final SampleStore store = mock(SampleStore.class);
    private final Host host = new Host("cached", "Cached", "10.7.1.1", List.of());
    private final List<PingSample> rows = new ArrayList<>();
    private final PacketLossAnalysisService service = new PacketLossAnalysisService(
        store, new PacketLossPolicy(), 1000, 2000
    );

    PacketLossCacheTests() {
        doAnswer(invocation -> {
            long cutoff = invocation.getArgument(0);
            Consumer<PingSample> consumer = invocation.getArgument(1);
            rows.stream().filter(row -> row.measuredAt() >= cutoff).forEach(consumer);
            return null;
        }).when(store).readRecent(anyLong(), any());
    }

    private PacketLossAnalysis current() {
        return service.current(List.of(host)).get(host.id());
    }

    @Test
    void readsBeforeFirstRefreshAndForUnknownHostsNeverQueryDatabase() {
        assertThat(current().stats().fiveMinutes()).isNull();
        assertThat(current().stats().attemptsEightHours()).isZero();
        verifyNoInteractions(store);
    }

    @Test
    void databaseWritesOnlyBecomeVisibleAfterBackgroundRefresh() {
        long now = 100_000_000L;
        rows.add(new PingSample(1, host.id(), host.ip(), now, 10d, null));
        service.refreshAt(now);
        var first = current();
        rows.add(new PingSample(2, host.id(), host.ip(), now + 1000, null, "Tempo esgotado"));
        for (int i = 0; i < 10; i++) assertThat(current()).isSameAs(first);
        verify(store, times(1)).readRecent(anyLong(), any());
        service.refreshAt(now + 10000);
        assertThat(current().stats().attemptsFiveMinutes()).isEqualTo(2);
        assertThat(current().stats().lossesFiveMinutes()).isEqualTo(1);
        service.refreshAt(now + 20000);
        assertThat(current().stats().attemptsFiveMinutes()).isEqualTo(2);
    }

    @Test
    void refreshExpiresWindowsWithoutNewPingsAndIncludesLateRecords() {
        long now = 100_000_000L;
        rows.add(new PingSample(1, host.id(), host.ip(), now - 299000, 10d, null));
        rows.add(new PingSample(2, host.id(), host.ip(), now - 3599000, 20d, null));
        rows.add(new PingSample(3, host.id(), host.ip(), now - 28799000, null, "Tempo esgotado"));
        service.refreshAt(now);
        assertThat(current().stats().fiveMinutes()).isEqualTo(10d);
        assertThat(current().stats().latencyHour()).isEqualTo(15d);
        assertThat(current().stats().attemptsEightHours()).isEqualTo(3);
        service.refreshAt(now + 10000);
        assertThat(current().stats().fiveMinutes()).isNull();
        assertThat(current().stats().latencyHour()).isEqualTo(10d);
        assertThat(current().stats().lossesEightHours()).isZero();
        rows.add(new PingSample(4, host.id(), host.ip(), now - 60000, 30d, null));
        service.refreshAt(now + 20000);
        assertThat(current().stats().fiveMinutes()).isEqualTo(30d);
        service.refreshAt(now + 8 * 3600000L);
        assertThat(current().stats().attemptsEightHours()).isZero();
    }

    @Test
    void failedOrInProgressRefreshKeepsPreviousSnapshot() {
        long now = 100_000_000L;
        rows.add(new PingSample(1, host.id(), host.ip(), now, 10d, null));
        service.refreshAt(now);
        var first = current();
        doAnswer(invocation -> {
            Consumer<PingSample> consumer = invocation.getArgument(1);
            consumer.accept(new PingSample(2, host.id(), host.ip(), now, 50d, null));
            assertThat(current()).isSameAs(first);
            throw new IllegalStateException("database unavailable");
        }).when(store).readRecent(anyLong(), any());
        assertThatThrownBy(() -> service.refreshAt(now + 10000)).isInstanceOf(IllegalStateException.class);
        assertThat(current()).isSameAs(first);
    }

    @Test
    void changedIpAndFutureRecordsDoNotLeakIntoCurrentIndicators() {
        long now = 100_000_000L;
        rows.add(new PingSample(1, host.id(), "10.7.1.2", now, 50d, null));
        rows.add(new PingSample(2, host.id(), host.ip(), now + 1000, 10d, null));
        service.refreshAt(now);
        assertThat(current().stats().attemptsEightHours()).isZero();
        service.refreshAt(now + 10000);
        assertThat(current().stats().attemptsEightHours()).isEqualTo(1);
    }
}
