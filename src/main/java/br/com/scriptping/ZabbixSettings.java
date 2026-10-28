package br.com.scriptping;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class ZabbixSettings {
    public record State(String url, String username, String password, Set<String> groups,
                        Set<String> hosts, List<Host> inventory, Long lastSync) {}
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private volatile State state;

    public ZabbixSettings(JdbcTemplate jdbc, ObjectMapper mapper,
        @Value("${zabbix.url}") String url, @Value("${zabbix.username}") String username,
        @Value("${zabbix.password}") String password) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        var rows = jdbc.queryForList("SELECT payload FROM zabbix_settings WHERE id = 1", String.class);
        try {
            state = rows.isEmpty() ? new State(url, username, password, Set.of(), Set.of(), List.of(), null)
                : mapper.readValue(rows.getFirst(), State.class);
        } catch (Exception e) { throw new IllegalStateException("Não foi possível ler a configuração Zabbix", e); }
    }

    public State current() { return state; }

    public synchronized void save(State next) {
        try {
            String payload = mapper.writeValueAsString(next);
            if (jdbc.update("UPDATE zabbix_settings SET payload = ? WHERE id = 1", payload) == 0)
                jdbc.update("INSERT INTO zabbix_settings (id, payload) VALUES (1, ?)", payload);
            state = next;
        } catch (Exception e) { throw new IllegalStateException("Não foi possível salvar a configuração", e); }
    }
}
