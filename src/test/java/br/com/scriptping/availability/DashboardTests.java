package br.com.scriptping.availability;

import br.com.scriptping.zabbix.Host;

import br.com.scriptping.zabbix.ZabbixService;

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
        String html = mvc.perform(get("/disponibilidade")).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        var matcher = java.util.regex.Pattern.compile("<select id=\"period\"[^>]*>(.*?)</select>",
            java.util.regex.Pattern.DOTALL).matcher(html);
        assertThat(matcher.find()).isTrue();
        String options = matcher.group(1);
        assertThat(java.util.regex.Pattern.compile("<option\\s").matcher(options).results().count()).isEqualTo(15);
        assertThat(options).contains("value=\"1051200\">Últimos 2 anos</option>");
        assertThat(options).contains("value=\"2880\" selected=\"selected\"");
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
        for (int minutes : AvailabilityDashboard.MINUTES) {
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
        mvc.perform(get("/api/availability").param("minutes", "31")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/availability").param("groupId", "missing")).andExpect(status().isNotFound());
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    MinuteAggregationScheduled aggregation;

    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    ZabbixService monitor;

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

    @Autowired
    AvailabilitySnapshotRepository snapshots;

    @Test
    void failedSnapshotReplacementRollsBackDeletionAndRetainsPublishedVersion() {
        var h=new Host("rollback-host", "Rollback", "127.0.0.4", java.util.List.of());
        org.mockito.Mockito.when(monitor.hosts()).thenReturn(java.util.List.of(h));
        insertSample(h,10d,null);
        aggregation.aggregate();
        var previous=snapshots.load();
        assertThat(previous).isNotNull();
        var invalid=new AvailabilitySnapshot(previous.format(), "invalid", -1, previous.to(),
            previous.load(), previous.hosts(), previous.periods());
        var payload=snapshots.encode(invalid);
        jdbc.execute("ALTER TABLE availability_snapshot ADD CONSTRAINT snapshot_positive_time CHECK (built_at>=0)");
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> snapshots.save(invalid,payload))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(snapshots.load().version()).isEqualTo(previous.version());
            assertThat(availabilityDashboard.query(5, "").view().hosts().getFirst().id()).isEqualTo(h.id());
        } finally {
            jdbc.execute("ALTER TABLE availability_snapshot DROP CONSTRAINT snapshot_positive_time");
        }
    }

    private void insertSample(Host host, Double latency, String error) {
        jdbc.update(
            "INSERT INTO ping_sample(host_id,target,measured_at,latency,error,latency_limit) VALUES(?,?,?,?,?,?)",
            host.id(), host.ip(), System.currentTimeMillis() - 60000, latency, error, 20d
        );
    }
}
