package br.com.scriptping.maps;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Moves legacy shared definitions into each map before retiring their table. */
@Component
public class MapConnectionMigration implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();

    public MapConnectionMigration(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void run(ApplicationArguments args) throws Exception {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE LOWER(table_name)='mapped_connection'", Integer.class) == 0) return;
        var definitions = jdbc.query("SELECT * FROM mapped_connection", (rs, n) -> new NetworkMapModels.Connection(
            rs.getString("id"), rs.getString("name"), rs.getString("host_a"), rs.getString("interface_a"), rs.getString("host_b"), rs.getString("interface_b")));
        var byId = definitions.stream().collect(java.util.stream.Collectors.toMap(NetworkMapModels.Connection::id, c -> c));
        for (var map : jdbc.queryForList("SELECT id,layout FROM network_map FOR UPDATE")) {
            var layout = (ObjectNode) json.readTree((String) map.get("layout"));
            boolean changed = false;
            for (var link : layout.withArray("links")) {
                if (!link.hasNonNull("mappedConnectionId") || link.hasNonNull("connection")) continue;
                var definition = byId.get(link.path("mappedConnectionId").asText());
                if (definition == null) throw new IllegalStateException("Definição de conexão ausente no mapa " + map.get("id"));
                ((ObjectNode) link).set("connection", json.valueToTree(definition));
                changed = true;
            }
            if (changed) jdbc.update("UPDATE network_map SET layout=?, revision=revision+1 WHERE id=?", json.writeValueAsString(layout), map.get("id"));
        }
        jdbc.execute("DROP TABLE mapped_connection");
    }
}
