package br.com.scriptping.availability;

import br.com.scriptping.maps.NetworkMapController;
import br.com.scriptping.maps.NetworkMapService;
import br.com.scriptping.zabbix.Host;
import br.com.scriptping.zabbix.ZabbixService;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MapMetricsTests {
    @Test void mapWeightsMeasurementsAndClassifiesTheSelectedMetricInsteadOfWorstHost() {
        var group = new Host.Group("g", "Rede");
        var a = new Host("a", "A", "10.0.0.1", List.of(group, group));
        var b = new Host("b", "B", "10.0.0.2", List.of(group));
        var period = new AvailabilitySnapshot.PeriodData(0, List.of(), Map.of(
            "a", data(999, 0, 9990), "b", data(0, 1, 0)));
        var repository = mock(AvailabilitySnapshotRepository.class);
        when(repository.load()).thenReturn(new AvailabilitySnapshot(2, "v", 100, 100, null, List.of(a,b), Map.of(60, period)));
        var builder = mock(PacketLossAnalysisService.class);
        var inventory = mock(ZabbixService.class);
        when(inventory.hosts()).thenReturn(List.of(a,b));
        var cache = new AvailabilitySnapshotCache(builder, repository, inventory);
        cache.restore();
        clearInvocations(repository);
        var controller = new NetworkMapController(new NetworkMapService(null, null, inventory, null), cache, new PacketLossPolicy());
        var result = controller.metrics(new NetworkMapController.MetricRequest(Set.of("g", "missing"), Set.of("a", "b"), 60));
        var combined = result.elements().stream().filter(e -> e.id().equals("g")).findFirst().orElseThrow();
        assertThat(combined.latency()).isEqualTo(10d);
        assertThat(combined.loss()).isEqualTo(.1);
        assertThat(combined.lossSummary().average()).isEqualTo(.1);
        assertThat(combined.lossSummary().maximum()).isEqualTo(100d);
        assertThat(combined.lossSummary().aboveLimit()).isEqualTo(1);
        assertThat(combined.lossSummary().hosts().getFirst().id()).isEqualTo("b");
        assertThat(combined.latencyState()).isEqualTo("NORMAL");
        assertThat(combined.lossState()).isEqualTo("NORMAL");
        var lost = result.elements().stream().filter(e -> e.id().equals("b")).findFirst().orElseThrow();
        assertThat(lost.latency()).isNull();
        assertThat(lost.latencyState()).isEqualTo("SEM_DADOS");
        assertThat(lost.loss()).isEqualTo(100d);
        assertThat(lost.lossState()).isEqualTo("CRITICO");
        assertThat(result.elements().stream().filter(e -> e.id().equals("missing")).findFirst().orElseThrow().loss()).isNull();
        var changed = new Host("a", "A", "10.0.0.9", List.of(group));
        assertThat(cache.mapMetrics(60, Set.of(), Set.of("a"), List.of(changed,b)).elements().getFirst().metrics().latency()).isNull();
        verifyNoInteractions(repository, builder);
        assertThatThrownBy(() -> controller.metrics(new NetworkMapController.MetricRequest(Set.of(), Set.of(), 123)))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test void latencyBoundariesAndLossPolicyRemainIndependent() {
        var cache = mock(AvailabilitySnapshotCache.class);
        var service = mock(NetworkMapService.class);
        when(service.inventoryHosts()).thenReturn(List.of());
        var elements = List.of(
            new AvailabilitySnapshotCache.MapMetric("host", "a", counts(15, 0)),
            new AvailabilitySnapshotCache.MapMetric("host", "b", counts(20, 1)),
            new AvailabilitySnapshotCache.MapMetric("host", "c", counts(21, 0)));
        when(cache.mapMetrics(60, Set.of(), Set.of("a", "b", "c"), List.of())).thenReturn(new AvailabilitySnapshotCache.MapMetrics(0, 100, "v", 100, elements));
        var result = new NetworkMapController(service, cache, new PacketLossPolicy())
            .metrics(new NetworkMapController.MetricRequest(Set.of(), Set.of("a", "b", "c"), 60));
        assertThat(result.elements()).extracting(NetworkMapController.MetricElement::latencyState)
            .containsExactly("ATENCAO", "ATENCAO", "CRITICO");
        assertThat(result.elements()).extracting(NetworkMapController.MetricElement::lossState)
            .containsExactly("NORMAL", "CRITICO", "NORMAL");
    }

    private static HostAvailabilityService.Metrics counts(double latency, long lost) {
        return new AvailabilitySnapshot.Observation(99, 0, lost, 0, 99 * latency, 100L, 1).metrics();
    }
    private static AvailabilitySnapshot.HostPeriod data(long healthy, long lost, double latencySum) {
        return new AvailabilitySnapshot.HostPeriod(new AvailabilitySnapshot.Observation(healthy, 0, lost, 0, latencySum, 100L, 1),
            List.of(), 0, "CRITICO", true, true);
    }
}
