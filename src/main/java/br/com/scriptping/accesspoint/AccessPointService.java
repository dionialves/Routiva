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
    public AccessPointService(AccessPointRepository repository, ZabbixService inventory, List<EquipmentProvider> providers) {
        this.repository = repository; this.inventory = inventory; this.providers = providers;
    }
    public record Row(String hostId, String name, String ip, String manufacturer, String model,
        Integer connectedClients, Long lastCollectedAt, boolean available, List<Host.Group> groups) {}
    @Transactional
    public List<Row> list() {
        var hosts = new HashMap<String, Host>();
        inventory.hosts().forEach(h -> hosts.put(h.id(), h));
        repository.all().forEach(ap -> { if (hosts.containsKey(ap.hostId())) repository.refresh(hosts.get(ap.hostId())); });
        return repository.all().stream().map(ap -> new Row(ap.hostId(), ap.name(), ap.ip(), ap.manufacturer(),
            ap.model(), ap.connectedClients(), ap.lastCollectedAt(), hosts.containsKey(ap.hostId()), hosts.containsKey(ap.hostId()) ? hosts.get(ap.hostId()).groups() : List.of())).toList();
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
    public EquipmentProvider.Snapshot read(String id, String section, String selectedInterface) {
        var host = host(id);
        var ap = repository.all().stream().filter(a -> a.hostId().equals(id)).findFirst().orElseThrow();
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
