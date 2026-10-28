package br.com.scriptping;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class AvailabilityDashboardTests {
    private PingSample sample(long id, long at, Double latency) {
        return new PingSample(id, "host", "127.0.0.1", at, latency, latency == null ? "Tempo esgotado" : null, 20);
    }
    @Test void fiveMinutesCutsExactEdgesAndPreservesHourlyReports() {
        long now = 3_630_123;
        var history = new AvailabilityHistory();
        history.add(sample(1, now - 300_001, null));
        history.add(sample(2, now - 300_000, 10d));
        history.add(sample(3, now - 1, 30d));
        history.add(sample(4, now, null));
        var reports = history.reports(now, 48 * 3_600_000L, "v1", true);
        assertThat(reports.get(0).from()).isEqualTo(now - 300_000);
        assertThat(reports.get(0).total().total()).isEqualTo(2);
        assertThat(reports.get(0).total().technical()).isEqualTo(100);
        assertThat(reports.get(0).total().operational()).isEqualTo(50);
        assertThat(reports.get(1).total().total()).isEqualTo(3);
        assertThat(reports.get(0).series().stream().mapToLong(p -> p.metrics().total()).sum()).isEqualTo(2);
    }
    @Test void aggregatesCountsAndResponseWeightedLatencyInsteadOfHostPercentages() {
        long now = 600_001;
        var first = new AvailabilityHistory();
        first.add(sample(1, now - 1, 10d));
        var second = new AvailabilityHistory();
        second.add(sample(2, now - 1, 30d));
        second.add(sample(3, now - 1, 50d));
        second.add(sample(4, now - 1, null));
        var empty = new AvailabilityHistory().reports(now, 172800000, "v", true).get(0);
        var report = AvailabilityDashboard.combine(List.of(first.reports(now, 172800000, "v", true).get(0),
            second.reports(now, 172800000, "v", true).get(0)), empty);
        assertThat(report.total().technical()).isEqualTo(75);
        assertThat(report.total().operational()).isEqualTo(25);
        assertThat(report.total().loss()).isEqualTo(25);
        assertThat(report.total().latency()).isEqualTo(30);
        assertThat(report.series().getLast().metrics()).isEqualTo(report.total());
        assertThat(report.series().getFirst().metrics().technical()).isNull();
        assertThat(AvailabilityDashboard.combine(List.of(), empty).total().technical()).isNull();
    }
    @Test void validatesPeriodsAndReturnsLoadingWithoutTriggeringComputation() {
        var controller = new AvailabilityDashboardController(new AvailabilityDashboard(null, null));
        assertThat(controller.data(2880, "").getBody().view()).isNull();
        assertThat(controller.data(5, "").getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThatThrownBy(() -> controller.data(30, "")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
    @Test void serverPaginationSearchAndSortingKeepGlobalMetrics() {
        var report = new AvailabilityHistory().reports(600000, 172800000, "v", true).get(0);
        var rows = java.util.stream.IntStream.range(0, 1000).mapToObj(i -> new AvailabilityDashboard.Row(
            "id-" + i, String.format("Roteador %04d", i), report.total(), "10.0.0." + i,
            List.of(new Host.Group("g", "Produção")), "SEM_DADOS", false, false)).toList();
        var view = new AvailabilityDashboard.View(report, rows);
        var service = new AvailabilityDashboard(null, null) {
            @Override public Publication current() { return new Publication(List.of(), java.util.Map.of("", java.util.Map.of(5, view))); }
        };
        var controller = new AvailabilityDashboardController(service);
        var first = controller.dashboard(5, "", "", "name", "asc", 1).getBody();
        assertThat(first.view().hosts()).hasSize(20);
        assertThat(first.page().total()).isEqualTo(1000);
        assertThat(first.page().pages()).isEqualTo(50);
        assertThat(first.view().report()).isSameAs(report);
        var last = controller.dashboard(5, "", "", "name", "desc", 1).getBody();
        assertThat(last.view().hosts().getFirst().id()).isEqualTo("id-999");
        var found = controller.dashboard(5, "", "10.0.0.999", "name", "asc", 99).getBody();
        assertThat(found.view().hosts()).hasSize(1);
        assertThat(found.page().number()).isEqualTo(1);
        assertThat(found.page().selectedTotal()).isEqualTo(1000);
        assertThat(found.collectionStatus()).isEqualTo("SEM_DADOS");
        assertThat(controller.dashboard(5, "", "missing", "name", "asc", 1).getBody().view().hosts()).isEmpty();
    }
}
