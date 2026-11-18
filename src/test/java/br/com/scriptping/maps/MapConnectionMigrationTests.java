package br.com.scriptping.maps;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

@JdbcTest
class MapConnectionMigrationTests {
    @Autowired JdbcTemplate jdbc;

    @Test void embedsLegacyDefinitionsInEveryMapBeforeDroppingTheOldTable() throws Exception {
        jdbc.execute("CREATE TABLE mapped_connection(id VARCHAR(36), name VARCHAR(120), host_a VARCHAR(100), interface_a VARCHAR(128), host_b VARCHAR(100), interface_b VARCHAR(128))");
        try {
            jdbc.update("INSERT INTO mapped_connection VALUES ('c1','Uplink','a','ether1','b','ether2')");
            String layout = "{\"name\":\"Rede\",\"nodes\":[],\"links\":[{\"from\":\"a\",\"to\":\"b\",\"mappedConnectionId\":\"c1\"},{\"from\":\"c\",\"to\":\"d\"}]}";
            for (String id : java.util.List.of("one", "two"))
                jdbc.update("INSERT INTO network_map(id,revision,layout) VALUES (?,7,?)", id, layout);
            var migration = new MapConnectionMigration(jdbc);
            migration.run(null);
            var json = new ObjectMapper();
            for (String stored : jdbc.queryForList("SELECT layout FROM network_map", String.class)) {
                var links = json.readTree(stored).path("links");
                assertThat(links.get(0).path("connection").path("interfaceA").asText()).isEqualTo("ether1");
                assertThat(links.get(1).has("connection")).isFalse();
            }
            assertThat(jdbc.queryForList("SELECT revision FROM network_map", Long.class)).containsOnly(8L);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE LOWER(table_name)='mapped_connection'", Integer.class)).isZero();
            migration.run(null); // Repeated starts have no legacy table to migrate.
        } finally { jdbc.execute("DROP TABLE IF EXISTS mapped_connection"); }
    }

    @Test void missingDefinitionStopsMigrationWithoutDroppingLegacyData() {
        jdbc.execute("CREATE TABLE mapped_connection(id VARCHAR(36), name VARCHAR(120), host_a VARCHAR(100), interface_a VARCHAR(128), host_b VARCHAR(100), interface_b VARCHAR(128))");
        try {
            jdbc.update("INSERT INTO network_map(id,revision,layout) VALUES ('orphan',1,?)",
                "{\"links\":[{\"mappedConnectionId\":\"missing\"}]}");
            assertThatThrownBy(() -> new MapConnectionMigration(jdbc).run(null)).isInstanceOf(IllegalStateException.class);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE LOWER(table_name)='mapped_connection'", Integer.class)).isEqualTo(1);
        } finally { jdbc.execute("DROP TABLE mapped_connection"); }
    }
}
