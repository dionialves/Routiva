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

    public List<Host> inventoryHosts() { return inventory.hosts(); }

    public List<Host.Group> groups() {
        return inventory.hosts().stream().flatMap(host -> host.groups().stream()).distinct()
            .sorted(Comparator.comparing(Host.Group::name)).toList();
    }

    public List<HostOption> hosts() {
        return inventory.hosts().stream().map(h -> new HostOption(h.id(), h.name(), h.ip(), h.groups()))
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
        var hostIds = inventory.hosts().stream()
            .filter(h -> targets.hosts().contains(h.id())
                || h.groups().stream().anyMatch(g -> targets.groups().contains(g.id())))
            .map(Host::id).collect(java.util.stream.Collectors.toSet());
        if (hostIds.isEmpty()) return List.of();
        try {
            var hosts = zabbix.mapHosts(Set.of(), hostIds).stream()
                .filter(h -> hostIds.contains(h.id())).toList();
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
                || (node.subMapId() != null && (node.groupId() == null || node.groupId().isBlank()
                    || node.subMapId().equals(input.id()) || repository.findById(node.subMapId()).isEmpty()))
                || (node.color() != null && !node.color().matches("#[0-9a-fA-F]{6}"))
                || (node.switchColor() != null && !node.switchColor().matches("#[0-9a-fA-F]{6}"))
                || node.name() == null) throw invalid();
        }
        var nodes = layout.nodes().stream().collect(java.util.stream.Collectors.toMap(Node::id, n -> n));
        for (var node : layout.nodes()) {
            if (!Set.of("circle", "switch").contains(node.icon())) throw invalid();
            var capacities = Map.of("electrical", "rj45", "sfp1g", "sfp", "sfp10g", "sfp", "sfp40g", "qsfp", "sfp100g", "qsfp");
            var connectors = Map.of("electrical", "rj45", "sfp", "sfp", "sfpplus", "sfp", "sfp28", "sfp", "qsfpplus", "qsfp",
                "sfp56", "sfp", "qsfp28", "qsfp", "qsfp56", "qsfp", "qsfpdd", "qsfp", "qsfpdd800", "qsfp");
            var speeds = Set.of("unknown", "10m", "100m", "1g", "10g", "25g", "40g", "50g", "100g", "200g", "400g", "800g");
            var slots = new HashMap<String, Slot>();
            for (var slot : node.slots()) {
                if (slot == null || slot.id() == null || slot.id().isBlank() || slot.id().length() > 80
                    || slots.putIfAbsent(slot.id(), slot) != null || slot.name() == null || slot.name().isBlank()
                    || slot.name().length() > 80 || slot.capacity() == null
                    || (slot.connector() == null ? !capacities.containsKey(slot.capacity())
                        : !connectors.containsKey(slot.connector()) || !speeds.contains(slot.capacity()))
                    || slot.count() < 1 || slot.count() > 96) throw invalid();
            }
            if (!slots.isEmpty() && node.slots().stream().mapToLong(Slot::count).sum() != node.ports().size()) throw invalid();
            for (var slot : node.slots()) {
                if (slot.interfaceNames() != null && (slot.interfaceNames().size() != slot.count()
                    || slot.interfaceNames().stream().anyMatch(n -> n == null || n.isBlank() || n.length() > 128)
                    || new HashSet<>(slot.interfaceNames()).size() != slot.count())) throw invalid();
            }
            var slotCounts = new HashMap<String, Integer>();
            var portIds = new HashSet<String>();
            for (var port : node.ports()) {
                if (port == null || port.id() == null || port.id().isBlank() || port.id().length() > 80
                    || !portIds.add(port.id()) || port.type() == null
                    || !Set.of("rj45", "sfp", "qsfp").contains(port.type())) throw invalid();
                if (!slots.isEmpty() || port.slotId() != null) {
                    var slot = slots.get(port.slotId());
                    if (slot == null) throw invalid();
                    int index = slotCounts.merge(slot.id(), 1, Integer::sum);
                    if (index > slot.count() || !slot.capacity().equals(port.capacity())
                        || !java.util.Objects.equals(slot.connector(), port.connector())
                        || !(slot.connector() == null ? capacities.get(slot.capacity()) : connectors.get(slot.connector())).equals(port.type())
                        || !(slot.interfaceNames() == null ? slot.name() + index : slot.interfaceNames().get(index - 1)).equals(port.name())) throw invalid();
                }
            }
        }
        var pairs = new HashSet<Set<List<String>>>();
        var occupied = new HashSet<List<String>>();
        for (var link : layout.links()) {
            if (link == null || !ids.contains(link.from()) || !ids.contains(link.to()) || link.from().equals(link.to())) throw invalid();
            if (!Set.of("straight", "corners").contains(link.style())
                || !Set.of("straight", "curved").contains(link.lineStyle())) throw invalid();
            if ((link.mappedConnectionId() == null) != (link.connection() == null)) throw invalid();
            if (link.mappedConnectionId() != null) {
                var c = link.connection();
                if (c == null || !link.mappedConnectionId().equals(c.id()) || c.name() == null
                    || c.name().isBlank() || c.name().length() > 120
                    || c.interfaceA() == null || c.interfaceA().isBlank() || c.interfaceA().length() > 128
                    || c.interfaceB() == null || c.interfaceB().isBlank() || c.interfaceB().length() > 128
                    || c.hostA() == null || c.hostA().equals(c.hostB())
                    || !Objects.equals(nodes.get(link.from()).hostId(), c.hostA())
                    || !Objects.equals(nodes.get(link.to()).hostId(), c.hostB())) throw invalid();
            }
            for (var point : link.points()) {
                if (point == null || !Double.isFinite(point.x()) || !Double.isFinite(point.y())) throw invalid();
            }
            var endpoints = List.of(List.of(link.from(), link.fromPort() == null ? "" : link.fromPort()),
                List.of(link.to(), link.toPort() == null ? "" : link.toPort()));
            if (!pairs.add(Set.copyOf(endpoints))) throw invalid();
            for (var endpoint : endpoints) {
                if (endpoint.get(1).isEmpty()) continue;
                if (nodes.get(endpoint.get(0)).ports().stream().noneMatch(port -> port.id().equals(endpoint.get(1)))
                    || !occupied.add(endpoint)) throw invalid();
            }
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
