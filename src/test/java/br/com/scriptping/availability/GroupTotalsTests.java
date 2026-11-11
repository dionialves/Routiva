package br.com.scriptping.availability;

import br.com.scriptping.zabbix.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GroupTotalsTests {
    @Test void totalsUseCountersAndValidLatencySamplesWithoutReadingHistory() {
        var group = new Host.Group("g", "Grupo");
        var a = new Host("a", "A", "10.0.0.1", List.of(group));
        var b = new Host("b", "B", "10.0.0.2", List.of(group));
        var one = new AvailabilitySnapshot.Observation(90, 5, 5, 7, 950, 100L, 10);
        var two = new AvailabilitySnapshot.Observation(0, 0, 1, 3, 0, 100L, 2);
        var period = new AvailabilitySnapshot.PeriodData(0, List.of(), Map.of(
            "a", new AvailabilitySnapshot.HostPeriod(one, List.of(), 0, "DEGRADADO", true, true),
            "b", new AvailabilitySnapshot.HostPeriod(two, List.of(), 0, "CRITICO", true, true)));
        var repository = mock(AvailabilitySnapshotRepository.class);
        when(repository.load()).thenReturn(new AvailabilitySnapshot(2, "v", 100, 100, null, List.of(a,b), Map.of(1440, period)));
        var builder = mock(PacketLossAnalysisService.class);
        var monitor = mock(ZabbixService.class);
        var cache = new AvailabilitySnapshotCache(builder, repository, monitor);
        cache.restore(); clearInvocations(repository);
        var row = cache.groupTotals(1440, List.of(a,b)).groups().getFirst();
        assertThat(row.metrics().technical()).isCloseTo(100d * 95 / 101, within(.00001));
        assertThat(row.metrics().operational()).isCloseTo(100d * 90 / 101, within(.00001));
        assertThat(row.metrics().loss()).isCloseTo(100d * 6 / 101, within(.00001));
        assertThat(row.metrics().latency()).isEqualTo(10d);
        assertThat(row.metrics().errors()).isEqualTo(10);
        assertThat(row.observedMillis()).isEqualTo(360000);
        assertThat(row.status()).isEqualTo("CRITICO");
        var changed = new Host("a", "A", "10.0.0.9", List.of(group));
        var noData = cache.groupTotals(1440, List.of(changed)).groups().getFirst();
        assertThat(noData.metrics().technical()).isNull();
        assertThat(noData.status()).isEqualTo("SEM_DADOS");
        verifyNoInteractions(repository, builder, monitor);
    }
}
