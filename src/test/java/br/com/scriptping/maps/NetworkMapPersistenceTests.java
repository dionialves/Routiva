package br.com.scriptping.maps;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import static br.com.scriptping.maps.NetworkMapModels.*;
import static org.assertj.core.api.Assertions.*;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@Import({NetworkMapService.class, NetworkMapPersistenceTests.Config.class})
class NetworkMapPersistenceTests {
    @TestConfiguration
    static class Config {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean br.com.scriptping.zabbix.ZabbixService inventory() {
            return org.mockito.Mockito.mock(br.com.scriptping.zabbix.ZabbixService.class);
        }
        @Bean br.com.scriptping.zabbix.ZabbixClient zabbix() {
            return org.mockito.Mockito.mock(br.com.scriptping.zabbix.ZabbixClient.class);
        }
    }

    @Autowired NetworkMapService service;
    @Autowired NetworkMapRepository repository;
    @Autowired JdbcTemplate jdbc;

    @Test
    void createsAndUpdatesMapsRejectingStaleOrMissingRevisions() throws Exception {
        var layout = new Layout(null, "Rede", null, null, List.of(), List.of());
        var created = service.save(new Saved(null, 0, layout, null));
        assertThat(created.revision()).isEqualTo(1);
        assertThat(service.maps()).containsExactly(created);

        var updated = service.save(created);
        assertThat(updated.revision()).isEqualTo(2);
        assertThat(service.maps()).containsExactly(updated);
        assertThatThrownBy(() -> service.save(created))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                ex -> assertThat(ex.getStatusCode().value()).isEqualTo(409));
        assertThatThrownBy(() -> service.save(new Saved("missing", 1, layout, null)))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                ex -> assertThat(ex.getStatusCode().value()).isEqualTo(409));
        assertThat(repository.count()).isEqualTo(1);
        assertThat(service.maps()).containsExactly(updated);
    }

    @Test
    void persistsAcknowledgedIncidentVisibility() throws Exception {
        var layout = new Layout(null, "Rede", null, 0, false, List.of(), List.of());
        var saved = service.save(new Saved(null, 0, layout, null));
        assertThat(service.maps()).containsExactly(saved);
        assertThat(service.maps().getFirst().layout().showAcknowledged()).isFalse();
    }

    @Test
    void readsLegacyJsonAndOrdersTimestampsWithNullsLast() {
        String legacy = "{\"name\":\"Legado\",\"width\":800,\"height\":600,\"nodes\":[],\"links\":[]}";
        jdbc.update("INSERT INTO network_map VALUES (?, ?, ?, ?)", "legacy", 7, legacy, null);
        jdbc.update("INSERT INTO network_map VALUES (?, ?, ?, ?)", "b", 1, legacy, 200L);
        jdbc.update("INSERT INTO network_map VALUES (?, ?, ?, ?)", "a", 1, legacy, 200L);
        jdbc.update("INSERT INTO network_map VALUES (?, ?, ?, ?)", "older", 1, legacy, 100L);
        assertThat(service.maps()).extracting(Saved::id).containsExactly("a", "b", "older", "legacy");
        var saved = service.maps().getLast();
        assertThat(saved.revision()).isEqualTo(7);
        assertThat(saved.layout().publication()).isEqualTo(Publication.PUBLICADO);
        assertThat(saved.layout().minimumSeverity()).isZero();
        assertThat(saved.layout().showAcknowledged()).isTrue();
        assertThat(saved.updatedAt()).isNull();
    }

    @Test
    void rejectsInvalidTopologyBeforePersisting() {
        var layout = new Layout(null, "", null, null, List.of(), List.of());
        assertThatThrownBy(() -> service.save(new Saved(null, 0, layout, null)))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                ex -> assertThat(ex.getStatusCode().value()).isEqualTo(400));
        assertThat(repository.count()).isZero();
    }
}
