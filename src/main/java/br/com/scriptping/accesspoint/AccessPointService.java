package br.com.scriptping.accesspoint;

import br.com.scriptping.zabbix.*;
import br.com.scriptping.equipment.EquipmentProvider;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AccessPointService {
    private final AccessPointRepository repository;
    private final ZabbixService inventory;
    private final List<EquipmentProvider> providers;
    private final br.com.scriptping.hosts.HostAuthentication authentication;
    private final br.com.scriptping.hosts.HostConnections connections;
    private final br.com.scriptping.hosts.HostRepository hosts;
    public AccessPointService(AccessPointRepository repository, ZabbixService inventory, List<EquipmentProvider> providers,
        br.com.scriptping.hosts.HostAuthentication authentication, br.com.scriptping.hosts.HostConnections connections,
        br.com.scriptping.hosts.HostRepository hosts) {
        this.repository = repository; this.inventory = inventory; this.providers = providers;
        this.authentication = authentication; this.connections = connections; this.hosts = hosts;
    }
    public record Row(String hostId, String name, String ip, String manufacturer, String model,
        Integer connectedClients, Long lastCollectedAt, boolean available, List<Host.Group> groups, String status, String connectionMessage) {}

    @Transactional
    public List<Row> list() {
        var hosts = new HashMap<String, Host>();
        inventory.hosts().forEach(h -> hosts.put(h.id(), h));
        repository.all().forEach(ap -> { if (hosts.containsKey(ap.hostId())) repository.refresh(this.hosts.target(ap.hostId())); });
        return repository.all().stream().map(ap -> {
            var c = authentication.view(ap.hostId());
            return new Row(ap.hostId(), ap.name(), ap.ip(), c.manufacturer(), ap.model(), ap.connectedClients(),
                ap.lastCollectedAt(), hosts.containsKey(ap.hostId()), hosts.containsKey(ap.hostId()) ? hosts.get(ap.hostId()).groups() : List.of(), c.status(), c.message());
        }).toList();
    }

    public List<Host> candidates() {
        var registered = new HashSet<String>();
        repository.all().forEach(ap -> registered.add(ap.hostId()));
        var credentials = hosts.credentials();
        return inventory.hosts().stream().filter(h -> !registered.contains(h.id()))
            .filter(h -> credentials.get(h.id()) == null || !"Datacom".equals(credentials.get(h.id()).manufacturer())).sorted(Comparator.comparing(Host::name)).toList();
    }
    @Transactional
    public synchronized void define(List<String> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > 5000 || ids.stream().anyMatch(Objects::isNull))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Selecione entre 1 e 5000 hosts.");
        var hosts = new HashMap<String, Host>();
        inventory.hosts().forEach(h -> hosts.put(h.id(), h));
        if (!hosts.keySet().containsAll(ids)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Há hosts fora do inventário importado. Atualize a seleção.");
        for (String id : ids) {
            var credentials = this.hosts.credentials(id);
            if (credentials != null && "Datacom".equals(credentials.manufacturer()))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Datacom não é um Access Point. Abra o painel pelo menu do host.");
        }
        new LinkedHashSet<>(ids).forEach(id -> {
            repository.add(hosts.get(id));
            authentication.validateSaved(id);
        });
    }
    public Host pageHost(String id) {
        if (!repository.contains(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Host não definido como AP.");
        return hosts.target(id);
    }
    public Host host(String id) {
        var host = pageHost(id);
        if (inventory.hosts().stream().noneMatch(h -> h.id().equals(id)))
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AP indisponível no inventário importado.");
        return host;
    }
    public record ConfigurationView(String hostId, String name, String ip, String manufacturer, String login,
        Integer port, boolean enabled, boolean passwordConfigured, String status, String message, boolean tls) {}
    public record ConfigurationInput(String manufacturer, String login,
        @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY) String password,
        Integer port, boolean enabled, boolean tls) {
        public ConfigurationInput(String manufacturer, String login, String password, Integer port, boolean enabled) {
            this(manufacturer, login, password, port, enabled, false);
        }
        @Override public String toString() { return "AP configuration input [redacted]"; }
    }
    public record ConnectionResult(boolean success, String message) {}
    public ConfigurationView configuration(String id) {
        pageHost(id);
        var c = authentication.view(id);
        return new ConfigurationView(id, c.name(), c.ip(), c.manufacturer(), c.login(), c.port(), c.enabled(), c.passwordConfigured(), c.status(), c.message(), c.tls());
    }
    private br.com.scriptping.hosts.HostAuthentication.Input hostInput(ConfigurationInput input) {
        return new br.com.scriptping.hosts.HostAuthentication.Input(input.manufacturer(), input.login(), input.password(), input.port(), input.enabled(), input.tls());
    }
    public synchronized ConnectionResult test(String id, ConfigurationInput input) {
        pageHost(id);
        var result = authentication.test(id, hostInput(input));
        return new ConnectionResult(result.success(), result.message());
    }
    public synchronized ConfigurationView save(String id, ConfigurationInput input) {
        pageHost(id);
        authentication.save(id, hostInput(input));
        return configuration(id);
    }
    public synchronized void delete(String id) { pageHost(id); repository.delete(id); }
    public EquipmentProvider.Snapshot read(String id, String section, String selectedInterface) {
        var host = host(id);
        var c = authentication.view(id);
        if ("INACTIVE".equals(c.status())) {
            authentication.validateSaved(id);
            c = authentication.view(id);
        }
        connections.require(host);
        String manufacturer = c.manufacturer();
        var provider = providers.stream().filter(p -> p.manufacturer().equalsIgnoreCase(manufacturer)).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "Fabricante ainda não suportado."));
        if (!provider.capabilities().contains(section)) throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "Recurso não suportado.");
        var result = provider.read(host, section, selectedInterface);
        if (result.warning() == null || result.warning().isBlank()) {
            String model = section.equals("overview") && !result.rows().isEmpty() ? result.rows().getFirst().get("board-name") : null;
            if (section.equals("overview") || section.equals("wireless"))
                repository.observation(id, model, section.equals("wireless") ? (int) result.rows().stream().filter(row -> !"disconnected".equals(row.get("connection-status"))).count() : null, result.measuredAt());
        }
        return result;
    }
}
