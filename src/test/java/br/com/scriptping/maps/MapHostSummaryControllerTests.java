package br.com.scriptping.maps;

import br.com.scriptping.availability.*;
import br.com.scriptping.zabbix.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.embedded.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MapHostSummaryControllerTests {
    private final EmbeddedDatabase db = new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).build();
    private final JdbcTemplate jdbc = new JdbcTemplate(db);
    private final NetworkMapService maps = mock(NetworkMapService.class);
    private final HostAvailabilityService availability = mock(HostAvailabilityService.class);
    private final ZabbixClient zabbix = mock(ZabbixClient.class);
    private final MapPingController ping = mock(MapPingController.class);
    private final MapHostSummaryController controller = new MapHostSummaryController(maps, availability, zabbix, ping,
        new NamedParameterJdbcTemplate(db), new PacketLossPolicy(), new PopLatencyService(new NamedParameterJdbcTemplate(db), 20));

    @BeforeEach void setup() {
        jdbc.execute("CREATE TABLE ping_sample (host_id VARCHAR,target VARCHAR,measured_at BIGINT,latency DOUBLE)");
        when(maps.inventoryHosts()).thenReturn(List.of(new Host("1", "Router", "ip", List.of())));
        when(ping.status(any())).thenReturn(ResponseEntity.ok(List.of(new MapPingController.State("1", "online", 900L))));
        when(zabbix.activeIncidents(List.of("1"))).thenReturn(Map.of("1", List.of(new ZabbixClient.Incident("Active", 4))));
        when(zabbix.hostIncidentHistory("1", 5)).thenReturn(List.of(
            new ZabbixClient.HostIncident("2", "Resolved", 2, 800, true, false, 0),
            new ZabbixClient.HostIncident("1", "Active", 4, 700, false, false, 0)));
        var metrics = new HostAvailabilityService.Metrics(2, 0, 1, 66.67, 66.67, 33.33, 0d, 5.5, 0);
        when(availability.report("1", 24)).thenReturn(new HostAvailabilityService.Report(0, 1000, 1, metrics, List.of(), "v", 1000L, 900L, 0));
    }
    @AfterEach void close() { db.shutdown(); }

    @Test void usesPublishedOperationalMetricsAndActualPercentileAndCachesHoverRequests() {
        jdbc.update("INSERT INTO ping_sample VALUES ('1','ip',100,1),('1','ip',200,10),('1','old-ip',300,999)");
        var result = controller.summary("1").getBody();
        assertThat(result.metrics().operational()).isEqualTo(66.67);
        assertThat(result.p95()).isEqualTo(10d);
        assertThat(result.status()).isEqualTo("online");
        assertThat(result.active()).isEqualTo(1);
        assertThat(result.incidents()).extracting(ZabbixClient.HostIncident::resolved).containsExactly(true, false);
        controller.summary("1");
        verify(zabbix, times(1)).hostIncidentHistory("1", 5);
    }

    @Test void retiredRawSamplesDoNotBecomeAPercentileAndApiFailuresDoNotBecomeEmptyHistory() {
        jdbc.update("INSERT INTO ping_sample VALUES ('1','ip',100,1)");
        when(zabbix.hostIncidentHistory("1", 5)).thenThrow(new IllegalStateException());
        when(zabbix.activeIncidents(List.of("1"))).thenReturn(Map.of());
        var result = controller.summary("1").getBody();
        assertThat(result.p95()).isNull();
        assertThat(result.incidents()).isNull();
        assertThat(result.active()).isNull();
        assertThat(result.warning()).contains("indisponível");
    }

    @Test void missingCurrentPingPreservesHistoricalTimestampWithoutClaimingCurrentAvailability() {
        when(ping.status(any())).thenReturn(ResponseEntity.ok(List.of(
            new MapPingController.State("1", "unknown", null))));
        var result = controller.summary("1").getBody();
        assertThat(result.status()).isEqualTo("unknown");
        assertThat(result.updatedAt()).isEqualTo(900L);
        assertThat(result.activeSeverity()).isEqualTo(4);
    }

    @Test void rejectsHostsOutsideInventory() {
        assertThatThrownBy(() -> controller.summary("other")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verifyNoInteractions(zabbix);
    }
}
