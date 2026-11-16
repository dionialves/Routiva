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
    private final AccessPointSecrets secrets;
    public AccessPointService(AccessPointRepository repository, ZabbixService inventory, List<EquipmentProvider> providers, AccessPointSecrets secrets) {
        this.repository = repository; this.inventory = inventory; this.providers = providers; this.secrets = secrets;
    }
    public record Row(String hostId, String name, String ip, String manufacturer, String model,
        Integer connectedClients, Long lastCollectedAt, boolean available, List<Host.Group> groups, String status, String connectionMessage) {}

    @Transactional
    public List<Row> list() {
        var hosts = new HashMap<String, Host>();
        inventory.hosts().forEach(h -> hosts.put(h.id(), h));
        repository.all().forEach(ap -> { if (hosts.containsKey(ap.hostId())) repository.refresh(hosts.get(ap.hostId())); });
        var configurations = repository.configurations();
        return repository.all().stream().filter(ap -> configurations.containsKey(ap.hostId())).map(ap -> {
            var c = configurations.get(ap.hostId());
            if ("ACTIVE".equals(c.status()) && !Objects.equals(c.verifiedIp(), ap.ip())) {
                repository.connectionState(ap.hostId(), "CONNECTION_FAILED", "IP alterado no Zabbix. Salve a configuração para testar novamente.", null);
                c = repository.configuration(ap.hostId());
            }
            return new Row(ap.hostId(), ap.name(), ap.ip(), ap.manufacturer(),
            ap.model(), ap.connectedClients(), ap.lastCollectedAt(), hosts.containsKey(ap.hostId()), hosts.containsKey(ap.hostId()) ? hosts.get(ap.hostId()).groups() : List.of(), c.status(), c.message()); }).toList();
    }
    public List<Host> candidates() {
        var registered = new HashSet<String>();
        repository.all().forEach(ap -> registered.add(ap.hostId()));
        return inventory.hosts().stream().filter(h -> !registered.contains(h.id())).sorted(Comparator.comparing(Host::name)).toList();
    }
    @Transactional
    public synchronized void define(List<String> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > 5000 || ids.stream().anyMatch(Objects::isNull))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Selecione entre 1 e 5000 hosts.");
        var hosts = new HashMap<String, Host>();
        inventory.hosts().forEach(h -> hosts.put(h.id(), h));
        if (!hosts.keySet().containsAll(ids)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Há hosts fora do inventário importado. Atualize a seleção.");
        new LinkedHashSet<>(ids).forEach(id -> repository.add(hosts.get(id)));
    }
    public Host pageHost(String id) {
        var ap = repository.all().stream().filter(a -> a.hostId().equals(id)).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Host não definido como AP."));
        return inventory.hosts().stream().filter(h -> h.id().equals(id)).findFirst()
            .orElseGet(() -> new Host(ap.hostId(), ap.name(), ap.ip(), List.of()));
    }
    public Host host(String id) {
        if (!repository.contains(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Host não definido como AP.");
        return inventory.hosts().stream().filter(h -> h.id().equals(id)).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AP indisponível no inventário importado."));
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
        var host = pageHost(id);
        var c = repository.configuration(id);
        return new ConfigurationView(id, host.name(), host.ip(), c.manufacturer(), c.login(), c.port(), c.enabled(),
            c.encryptedPassword() != null, c.status(), c.message(), c.tls());
    }
    private EquipmentProvider provider(String manufacturer) {
        if (!"MikroTik".equals(manufacturer)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Selecione o fabricante homologado: MikroTik RouterOS 6.x.");
        return providers.stream().filter(p -> p.manufacturer().equals(manufacturer)).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "Fabricante ainda não suportado."));
    }
    private EquipmentProvider.Connection connection(String id, ConfigurationInput input) {
        pageHost(id);
        provider(input.manufacturer());
        if (input.login() == null || input.login().isBlank() || input.login().length() > 255
                || input.port() == null || input.port() < 1 || input.port() > 65535
                || (input.password() != null && input.password().length() > 4096))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe login e porta API válida (1–65535).");
        String password = input.password();
        if (password == null || password.isEmpty()) {
            String saved = repository.configuration(id).encryptedPassword();
            if (saved == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe a senha do AP.");
            password = secrets.decrypt(saved);
        }
        return new EquipmentProvider.Connection(input.login().trim(), password, input.port(), input.tls());
    }
    private ConnectionResult attempt(String id, String manufacturer, EquipmentProvider.Connection connection, Host host) {
        try {
            if (inventory.hosts().stream().noneMatch(h -> h.id().equals(id)))
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AP indisponível no inventário importado.");
            provider(manufacturer).testConnection(host, connection);
            return new ConnectionResult(true, "Conexão validada com MikroTik RouterOS 6.x.");
        } catch (ResponseStatusException ex) {
            return new ConnectionResult(false, Objects.requireNonNullElse(ex.getReason(), "Falha na conexão com o AP."));
        }
    }
    public synchronized ConnectionResult test(String id, ConfigurationInput input) {
        return attempt(id, input.manufacturer(), connection(id, input), pageHost(id));
    }
    public synchronized ConfigurationView save(String id, ConfigurationInput input) {
        var connection = connection(id, input);
        var testedHost = pageHost(id);
        var result = input.enabled() ? attempt(id, input.manufacturer(), connection, testedHost)
            : new ConnectionResult(false, "AP desativado.");
        repository.configure(id, new AccessPointRepository.Configuration(input.manufacturer(), connection.login(),
            secrets.encrypt(connection.password()), connection.port(), input.enabled(),
            !input.enabled() ? "INACTIVE" : result.success() ? "ACTIVE" : "CONNECTION_FAILED", result.message(),
            result.success() ? testedHost.ip() : null, input.tls()));
        return configuration(id);
    }
    public synchronized void delete(String id) { pageHost(id); repository.delete(id); }
    public EquipmentProvider.Snapshot read(String id, String section, String selectedInterface) {
        var host = host(id);
        var ap = repository.all().stream().filter(a -> a.hostId().equals(id)).findFirst().orElseThrow();
        var c = repository.configuration(id);
        if (!c.enabled() || !"ACTIVE".equals(c.status()) || !Objects.equals(c.verifiedIp(), host.ip()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Configure e valide a conexão do AP antes de iniciar a coleta.");
        var provider = providers.stream().filter(p -> p.manufacturer().equalsIgnoreCase(ap.manufacturer())).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "Fabricante ainda não suportado."));
        if (!provider.capabilities().contains(section)) throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "Recurso não suportado.");
        var result = provider.read(host, section, selectedInterface);
        if (result.warning() == null || result.warning().isBlank()) {
            String model = section.equals("overview") && !result.rows().isEmpty() ? result.rows().getFirst().get("board-name") : null;
            if (section.equals("overview") || section.equals("wireless"))
                repository.observation(id, model, section.equals("wireless") ? result.rows().size() : null, result.measuredAt());
        }
        return result;
    }
}
