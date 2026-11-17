package br.com.scriptping.maps;

import br.com.scriptping.zabbix.Host;
import br.com.scriptping.zabbix.ZabbixService;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MapPingControllerTests {
    private final org.springframework.jdbc.datasource.embedded.EmbeddedDatabase database = new EmbeddedDatabaseBuilder()
        .generateUniqueName(true).setType(EmbeddedDatabaseType.H2).build();
    private final JdbcTemplate jdbc = new JdbcTemplate(database);
    private final ZabbixService inventory = mock(ZabbixService.class);
    private final MapPingController controller = new MapPingController(inventory, new NamedParameterJdbcTemplate(database));

    MapPingControllerTests() {
        jdbc.execute("CREATE TABLE ping_sample (id BIGINT PRIMARY KEY,host_id VARCHAR,target VARCHAR,measured_at BIGINT,latency DOUBLE,error VARCHAR)");
    }

    @AfterEach void close() { database.shutdown(); }

    @Test void usesLatestPingForCurrentTargetAndDoesNotTreatCollectionErrorsAsOffline() {
        when(inventory.hosts()).thenReturn(List.of(
            new Host("up", "Up", "10.0.0.1", List.of()), new Host("down", "Down", "10.0.0.2", List.of()),
            new Host("error", "Error", "10.0.0.3", List.of()), new Host("stale", "Stale", "10.0.0.4", List.of()),
            new Host("missing", "Missing", "10.0.0.5", List.of()), new Host("paused", "Paused", "10.0.0.6", List.of(), true)));
        long now = System.currentTimeMillis();
        sample(1, "up", "10.0.0.1", now - 1000, 80d, null);
        sample(2, "up", "old-ip", now - 500, null, "Tempo esgotado");
        sample(3, "down", "10.0.0.2", now - 2000, 1d, null);
        sample(4, "down", "10.0.0.2", now - 1000, null, "Sem resposta ICMP");
        sample(5, "error", "10.0.0.3", now - 1000, null, "Falha na coleta");
        sample(6, "stale", "10.0.0.4", now - 20000, 1d, null);
        sample(7, "paused", "10.0.0.6", now - 1000, 1d, null);
        var response = controller.status(new NetworkMapModels.Targets(Set.of(),
            Set.of("up", "down", "error", "stale", "missing", "paused", "unknown")));
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getBody()).extracting(MapPingController.State::state)
            .containsExactly("online", "offline", "unknown", "unknown", "unknown", "online");
    }

    @Test void resolvesEqualTimestampsBySampleIdAndRejectsInvalidRequests() {
        var group = new Host.Group("g", "Group");
        when(inventory.hosts()).thenReturn(List.of(new Host("1", "Switch", "ip", List.of(group))));
        long now = System.currentTimeMillis() - 500;
        sample(1, "1", "ip", now, 2d, null);
        sample(2, "1", "ip", now, null, "Tempo esgotado");
        assertThat(controller.status(new NetworkMapModels.Targets(Set.of("g"), Set.of())).getBody())
            .extracting(MapPingController.State::state).containsExactly("offline");
        assertThatThrownBy(() -> controller.status(new NetworkMapModels.Targets(null, Set.of())))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    private void sample(long id, String host, String target, long at, Double latency, String error) {
        jdbc.update("INSERT INTO ping_sample VALUES (?,?,?,?,?,?)", id, host, target, at, latency, error);
    }
}
