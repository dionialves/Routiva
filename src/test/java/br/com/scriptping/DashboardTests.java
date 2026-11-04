package br.com.scriptping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import br.com.scriptping.aggregation.MinuteAggregationScheduled;

@SpringBootTest(
    properties = {
        "indicators.aggregation.initial-delay-ms=3600000",
        "spring.datasource.url=jdbc:h2:mem:test;DB_CLOSE_DELAY=-1",
        "zabbix.url=",
        "zabbix.username=",
        "zabbix.password=",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "monitor.loss.minimum-coverage-ratio=0",
        "monitor.loss.minimum-samples=1",
    }
)
@AutoConfigureMockMvc
class DashboardTests {

    @Test
    void availabilityRouteIsAccessible() throws Exception {
        mvc.perform(get("/disponibilidade")).andExpect(status().isOk());
    }

    @Test
    void removedPagesAreNotAccessibleAndHomeOpensAvailability() throws Exception {
        mvc.perform(get("/")).andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/disponibilidade"));
        for (String path : java.util.List.of("/hosts", "/grupos", "/hosts/details", "/dashboard")) {
            mvc.perform(get(path)).andExpect(status().isNotFound());
            mvc.perform(get(path).header("HX-Request", "true")).andExpect(status().isNotFound());
        }
    }

    @Autowired
    AvailabilityDashboard availabilityDashboard;

    @Test
    void availabilityFiltersGroupsAndReadsMinuteSummaries() throws Exception {
        var first = new Host("av-a", "Host A", "127.0.0.1", java.util.List.of(new Host.Group("av-g", "Grupo A")));
        var second = new Host("av-b", "Host B", "127.0.0.2", java.util.List.of());
        org.mockito.Mockito.when(monitor.hosts()).thenReturn(java.util.List.of(first, second));
        insertSample(first, 10d, null);
        insertSample(second, null, "Tempo esgotado");
        aggregation.aggregate();
        for (int minutes : new int[] {5, 60, 480, 1440, 2880}) {
            mvc.perform(get("/api/availability").param("minutes", String.valueOf(minutes)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.view.hosts.length()").value(2))
                .andExpect(jsonPath("$.view.report.total.technical").value(50));
            mvc.perform(get("/api/availability").param("minutes", String.valueOf(minutes)).param("groupId", "av-g"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.view.hosts.length()").value(1))
                .andExpect(jsonPath("$.view.report.total.technical").value(100));
        }
        mvc.perform(get("/api/availability/dashboard").param("minutes", "5").param("q", "av-a"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.view.hosts.length()").value(1))
            .andExpect(jsonPath("$.view.hosts[0].ip").value("127.0.0.1"))
            .andExpect(jsonPath("$.page.selectedTotal").value(2))
            .andExpect(jsonPath("$.view.report.total.technical").value(50));
        mvc.perform(get("/api/availability").param("minutes", "30")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/availability").param("groupId", "missing")).andExpect(status().isNotFound());
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    MinuteAggregationScheduled aggregation;

    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    MonitorService monitor;

    @Test
    void persistsFailuresWithoutTurningThemIntoZero() {
        Host h = new Host("test", "Teste", "127.0.0.1", java.util.List.of());
        insertSample(h, 10d, null);
        insertSample(h, null, "Tempo esgotado");
        aggregation.aggregate();
        var result = availabilityDashboard.query(5, "");
        var summaries = jdbc.queryForMap("SELECT SUM(total_pings) AS total, SUM(lost) AS lost, SUM(latency_sum) AS latency FROM ping_minute WHERE host_id='test'");
        assertThat(((Number)summaries.get("total")).longValue()).isEqualTo(2);
        assertThat(((Number)summaries.get("lost")).longValue()).isEqualTo(1);
        assertThat(((Number)summaries.get("latency")).doubleValue()).isEqualTo(10d);
    }

    private void insertSample(Host host, Double latency, String error) {
        jdbc.update(
            "INSERT INTO ping_sample(host_id,target,measured_at,latency,error,latency_limit) VALUES(?,?,?,?,?,?)",
            host.id(), host.ip(), System.currentTimeMillis() - 60000, latency, error, 20d
        );
    }
}
