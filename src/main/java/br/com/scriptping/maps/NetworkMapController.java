package br.com.scriptping.maps;

import br.com.scriptping.zabbix.Host;
import br.com.scriptping.zabbix.ZabbixClient;
import br.com.scriptping.zabbix.ZabbixService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/maps")
public class NetworkMapController {
    public enum LabelPosition { ABOVE, BELOW, LEFT, RIGHT }

    public record Node(String id, String groupId, String hostId, String color, String name, int x, int y, LabelPosition labelPosition) {
        public Node {
            // Existing maps identify link endpoints by their host group.
            if (id == null) id = groupId;
            if (labelPosition == null) labelPosition = LabelPosition.BELOW;
        }
    }
    public record Link(String from, String to) {}
    public enum Publication { RASCUNHO, PUBLICADO }

    public record Layout(Publication publication, String name, Integer width, Integer height, List<Node> nodes, List<Link> links) {
        public Layout {
            if (publication == null) publication = Publication.PUBLICADO;
            if (width == null) width = 1200;
            if (height == null) height = 700;
        }
    }
    public record Saved(String id, long revision, Layout layout, Long updatedAt) {}

    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final ZabbixService inventory;
    private final ZabbixClient zabbix;

    public NetworkMapController(JdbcTemplate db, ObjectMapper json, ZabbixService inventory, ZabbixClient zabbix) {
        this.db = db;
        this.json = json;
        this.inventory = inventory;
        this.zabbix = zabbix;
    }

    @GetMapping
    public List<Saved> maps() {
        return db.query("SELECT id, revision, layout, updated_at_ms FROM network_map ORDER BY updated_at_ms DESC NULLS LAST, id", (row, index) -> {
            try {
                return new Saved(row.getString(1), row.getLong(2), json.readValue(row.getString(3), Layout.class),
                    row.getObject(4, Long.class));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @GetMapping("/groups")
    public List<Host.Group> groups() {
        return inventory.hosts().stream().flatMap(host -> host.groups().stream()).distinct()
            .sorted(Comparator.comparing(Host.Group::name)).toList();
    }

    public record HostOption(String id, String name, String ip) {}

    @GetMapping("/hosts")
    public List<HostOption> hosts() {
        return inventory.hosts().stream().map(h -> new HostOption(h.id(), h.name(), h.ip()))
            .sorted(Comparator.comparing(HostOption::name)).toList();
    }

    public record Targets(Set<String> groups, Set<String> hosts) {}
    public record HostState(String id, String name, List<Host.Group> groups, List<ZabbixClient.Incident> incidents) {}

    @PostMapping("/status")
    public List<HostState> status(@RequestBody Set<String> groups) {
        return states(new Targets(groups, Set.of()));
    }

    @PostMapping("/states")
    public List<HostState> states(@RequestBody Targets targets) {
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

    @PostMapping
    public Saved save(@RequestBody Saved input) throws JsonProcessingException {
        var layout = input.layout();
        if (layout == null || layout.name() == null || layout.name().isBlank() || layout.name().length() > 120
            || layout.width() < 1 || layout.height() < 1
            || layout.nodes() == null || layout.links() == null) throw invalid();
        var ids = new HashSet<String>();
        for (var node : layout.nodes()) {
            if (node == null || node.id() == null || node.id().isBlank() || !ids.add(node.id())
                || (node.groupId() != null && node.hostId() != null)
                || (node.color() != null && !node.color().matches("#[0-9a-fA-F]{6}"))
                || node.name() == null || node.x() < 0 || node.x() > layout.width() || node.y() < 0 || node.y() > layout.height()) throw invalid();
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
            db.update("INSERT INTO network_map (id, revision, layout, updated_at_ms) VALUES (?, 1, ?, ?)", id, data, now);
            return new Saved(id, 1, layout, now);
        }
        if (db.update("UPDATE network_map SET layout=?, updated_at_ms=?, revision=revision+1 WHERE id=? AND revision=?",
            data, now, input.id(), input.revision()) != 1) throw new ResponseStatusException(HttpStatus.CONFLICT);
        return new Saved(input.id(), input.revision() + 1, layout, now);
    }

    private ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Topologia inválida");
    }
}
