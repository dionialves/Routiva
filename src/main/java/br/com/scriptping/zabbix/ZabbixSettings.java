package br.com.scriptping.zabbix;

import br.com.scriptping.Host;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ZabbixSettings {
    public record State(String url, String username, String password, Set<String> groups,
                        Set<String> hosts, List<Host> inventory, Long lastSync) {}
    private final ZabbixSettingsRepository repository;
    private final ObjectMapper mapper;
    private volatile State state;

    public ZabbixSettings(ZabbixSettingsRepository repository, ObjectMapper mapper,
        @Value("${zabbix.url}") String url, @Value("${zabbix.username}") String username,
        @Value("${zabbix.password}") String password) {
        this.repository = repository;
        this.mapper = mapper;
        try {
            var stored = repository.findById(1);
            state = stored.isEmpty() ? new State(url, username, password, Set.of(), Set.of(), List.of(), null)
                : mapper.readValue(stored.get().payload(), State.class);
        } catch (Exception e) { throw new IllegalStateException("Não foi possível ler a configuração Zabbix", e); }
    }

    public State current() { return state; }

    public synchronized void save(State next) {
        try {
            String payload = mapper.writeValueAsString(next);
            repository.saveAndFlush(new ZabbixSettingsEntity(payload));
            state = next;
        } catch (Exception e) { throw new IllegalStateException("Não foi possível salvar a configuração", e); }
    }
}
