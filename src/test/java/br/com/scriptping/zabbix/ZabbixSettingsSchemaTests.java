package br.com.scriptping.zabbix;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.assertj.core.api.Assertions.*;

class ZabbixSettingsSchemaTests {
    @Test void repairsMissingLegacyUniquenessWithoutChangingPayload() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:legacy-settings-uniqueness;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE zabbix_settings(id INTEGER NOT NULL CHECK(id=1), payload TEXT NOT NULL)");
        jdbc.update("INSERT INTO zabbix_settings VALUES(1, ?)", "preserved configuration");
        var schema = new ResourceDatabasePopulator(new ClassPathResource("schema.sql"));
        schema.execute(source);
        schema.execute(source);
        assertThat(jdbc.queryForObject("SELECT payload FROM zabbix_settings WHERE id=1", String.class)).isEqualTo("preserved configuration");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO zabbix_settings VALUES(1, ?)", "duplicate"))
            .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM zabbix_settings", Integer.class)).isEqualTo(1);
    }
    @Test void refusesToSilentlyDiscardConflictingLegacySettings() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:duplicate-settings-uniqueness;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE zabbix_settings(id INTEGER NOT NULL CHECK(id=1), payload TEXT NOT NULL)");
        jdbc.update("INSERT INTO zabbix_settings VALUES(1, ?), (1, ?)", "original", "different");
        var schema = new ResourceDatabasePopulator(new ClassPathResource("schema.sql"));
        assertThatThrownBy(() -> schema.execute(source)).isInstanceOf(org.springframework.jdbc.datasource.init.ScriptStatementFailedException.class);
        assertThat(jdbc.queryForList("SELECT payload FROM zabbix_settings", String.class)).containsExactlyInAnyOrder("original", "different");
    }
}
