package br.com.scriptping.maps;

import br.com.scriptping.zabbix.Host;
import br.com.scriptping.zabbix.ZabbixClient;
import br.com.scriptping.zabbix.ZabbixService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import static br.com.scriptping.maps.NetworkMapModels.*;

@Service
public class NetworkMapService {
    private final NetworkMapRepository repository;
    private final ObjectMapper json;
    private final ZabbixService inventory;
    private final ZabbixClient zabbix;

    public NetworkMapService(NetworkMapRepository repository, ObjectMapper json, ZabbixService inventory, ZabbixClient zabbix) {
        this.repository = repository;
        this.json = json;
        this.inventory = inventory;
        this.zabbix = zabbix;
    }

    @Transactional(readOnly = true)
    public List<Saved> maps() {
        return repository.findAllOrdered().stream().map(entity -> {
            try {
                return new Saved(entity.id(), entity.revision(), json.readValue(entity.layout(), Layout.class),
                    entity.updatedAt());
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
        }).toList();
    }

    public List<Host.Group> groups() {
        return inventory.hosts().stream().flatMap(host -> host.groups().stream()).distinct()
            .sorted(Comparator.comparing(Host.Group::name)).toList();
    }

    public List<HostOption> hosts() {
        return inventory.hosts().stream().map(h -> new HostOption(h.id(), h.name(), h.ip()))
            .sorted(Comparator.comparing(HostOption::name)).toList();
    }

    public List<HostState> status(Set<String> groups) {
        return states(new Targets(groups, Set.of()));
    }

    public List<HostState> states(Targets targets) {
        if (targets.groups() == null || targets.hosts() == null) throw invalid();
        var hosts = inventory.hosts().stream()
            .filter(h -> targets.hosts().contains(h.id()) || h.groups().stream().anyMatch(g -> targets.groups().contains(g.id()))).toList();
        try {
            var problems = zabbix.activeIncidents(hosts.stream().map(Host::id).toList());
            return hosts.stream().map(h -> new HostState(h.id(), h.name(), h.groups(), problems.get(h.id()))).toList();
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Zabbix indisponível", e);
        }
    }

    public List<HostState> summaries(Targets targets) {
        if (targets.groups() == null || targets.hosts() == null) throw invalid();
        var allowedGroups = groups().stream().map(Host.Group::id).collect(java.util.stream.Collectors.toSet());
        var allowedHosts = inventory.hosts().stream().map(Host::id).collect(java.util.stream.Collectors.toSet());
        var groupIds = new HashSet<>(targets.groups());
        groupIds.retainAll(allowedGroups);
        var hostIds = new HashSet<>(targets.hosts());
        hostIds.retainAll(allowedHosts);
        try {
            var hosts = zabbix.mapHosts(groupIds, hostIds);
            var problems = zabbix.activeIncidents(hosts.stream().map(ZabbixClient.MapHost::id).toList(), true);
            return hosts.stream().map(h -> new HostState(h.id(), h.name(), h.groups(),
                problems.get(h.id()), h.monitoring())).toList();
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Zabbix indisponível", e);
        }
    }

    @Transactional(rollbackFor = JsonProcessingException.class)
    public Saved save(Saved input) throws JsonProcessingException {
        var layout = input.layout();
        if (layout == null || layout.name() == null || layout.name().isBlank() || layout.name().length() > 120
            || layout.nodes() == null || layout.links() == null
            || layout.minimumSeverity() < 0 || layout.minimumSeverity() > 5) throw invalid();
        var camera = layout.camera();
        if (camera != null && (!Double.isFinite(camera.x()) || !Double.isFinite(camera.y())
            || !Double.isFinite(camera.scale()) || camera.scale() <= 0 || camera.scale() > 3)) throw invalid();
        var ids = new HashSet<String>();
        for (var node : layout.nodes()) {
            if (node == null || node.id() == null || node.id().isBlank() || !ids.add(node.id())
                || (node.groupId() != null && node.hostId() != null)
                || (node.color() != null && !node.color().matches("#[0-9a-fA-F]{6}"))
                || node.name() == null) throw invalid();
        }
        var pairs = new HashSet<Set<String>>();
        for (var link : layout.links()) {
            if (link == null || !ids.contains(link.from()) || !ids.contains(link.to()) || link.from().equals(link.to())
                || !pairs.add(Set.of(link.from(), link.to()))) throw invalid();
        }
        long now = System.currentTimeMillis();
        var data = json.writeValueAsString(layout);
        if (input.id() == null) {
            String id = UUID.randomUUID().toString();
            repository.save(new NetworkMapEntity(id, 1, data, now));
            return new Saved(id, 1, layout, now);
        }
        if (repository.updateLayout(input.id(), input.revision(), data, now) != 1) throw new ResponseStatusException(HttpStatus.CONFLICT);
        return new Saved(input.id(), input.revision() + 1, layout, now);
    }

    private ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Topologia inválida");
    }
}
