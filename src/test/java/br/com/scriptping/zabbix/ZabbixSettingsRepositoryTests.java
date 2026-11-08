package br.com.scriptping.zabbix;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.*;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ZabbixSettingsRepositoryTests {

    @Autowired ZabbixSettingsRepository repository;
    @Autowired JdbcTemplate jdbc;

    @Test
    void readsLegacyPayloadAndCommitsInsertAndUpdateWithoutDuplicatingSettings() throws Exception {
        var mapper = new ObjectMapper();
        var host = new Host("h", "Router", "10.0.0.1", List.of(new Host.Group("g", "Rede")), true);
        var original = new ZabbixSettings.State("https://zabbix.test/api", "user", "secret",
            Set.of("g"), Set.of("h"), List.of(host), 123L);
        repository.deleteAll();
        try {
            // Existing installations already store this JSON in a single row.
            jdbc.update("INSERT INTO zabbix_settings (id, payload) VALUES (1, ?)",
                mapper.writeValueAsString(original));
            var settings = new ZabbixSettings(repository, mapper, "", "", "");
            assertThat(settings.current()).isEqualTo(original);

            var changed = new ZabbixSettings.State(original.url(), "updated", "new-secret",
                Set.of(), Set.of(), List.of(), 456L);
            settings.save(changed);
            assertThat(new ZabbixSettings(repository, mapper, "", "", "").current()).isEqualTo(changed);
            assertThat(repository.count()).isEqualTo(1);

            repository.deleteAll();
            settings.save(original);
            assertThat(new ZabbixSettings(repository, mapper, "", "", "").current()).isEqualTo(original);
            assertThat(repository.count()).isEqualTo(1);
        } finally {
            repository.deleteAll();
        }
    }
}
