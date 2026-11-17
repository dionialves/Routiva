package br.com.scriptping.maps;

import br.com.scriptping.accesspoint.AccessPointRepository;
import br.com.scriptping.mikrotik.MikrotikService;
import br.com.scriptping.zabbix.Host;
import br.com.scriptping.zabbix.ZabbixService;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Controller
public class MappedConnectionController {
    private final JdbcTemplate jdbc;
    private final ZabbixService inventory;
    private final MikrotikService mikrotik;
    private final AccessPointRepository equipment;
    public MappedConnectionController(JdbcTemplate jdbc, ZabbixService inventory, MikrotikService mikrotik, AccessPointRepository equipment) {
        this.jdbc = jdbc; this.inventory = inventory; this.mikrotik = mikrotik; this.equipment = equipment;
    }
    public record Connection(String id, String name, String hostA, String interfaceA, String hostB, String interfaceB) {}
    public record Endpoint(long measuredAt, String name, boolean up, String rxBps, String txBps, String error) {}
    public record Reading(String id, Endpoint a, Endpoint b) {}
    private final Map<String, Endpoint> cache = new HashMap<>();

    @ModelAttribute
    public void noCache(jakarta.servlet.http.HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }

    @GetMapping("/conexoes") public String page() { return "connections"; }
    @GetMapping("/api/connections") @ResponseBody
    public List<Connection> all() {
        return jdbc.query("SELECT * FROM mapped_connection ORDER BY name, id", (rs, n) -> new Connection(
            rs.getString("id"), rs.getString("name"), rs.getString("host_a"), rs.getString("interface_a"), rs.getString("host_b"), rs.getString("interface_b")));
    }
    private Host host(String id) {
        var host = inventory.hosts().stream().filter(h -> h.id().equals(id)).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Equipamento não encontrado."));
        var config = equipment.configuration(id);
        if (config == null || !"MikroTik".equals(config.manufacturer()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Configure a API MikroTik do equipamento em Access Points.");
        return host;
    }
    @GetMapping("/api/connections/interfaces/{hostId}") @ResponseBody
    public List<Map<String, String>> interfaces(@PathVariable String hostId) {
        return mikrotik.query(host(hostId), "interfaces", null).rows().stream()
            .filter(row -> "ether".equals(row.get("type"))).toList();
    }
    @PostMapping("/api/connections") @ResponseBody
    public Connection save(@RequestBody Connection input) {
        if (input.name() == null || input.name().isBlank() || input.name().length() > 120
            || input.hostA() == null || input.hostB() == null || input.hostA().equals(input.hostB()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe nome e dois equipamentos diferentes.");
        for (var endpoint : List.of(new String[]{input.hostA(), Objects.toString(input.interfaceA(), "")}, new String[]{input.hostB(), Objects.toString(input.interfaceB(), "")})) {
            if (interfaces(endpoint[0]).stream().noneMatch(i -> endpoint[1].equals(i.get("name"))))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Interface Ethernet não encontrada.");
        }
        // Immutable definitions keep references in existing maps stable.
        var c = new Connection(UUID.randomUUID().toString(), input.name().trim(), input.hostA(), input.interfaceA(), input.hostB(), input.interfaceB());
        jdbc.update("INSERT INTO mapped_connection(id,name,host_a,interface_a,host_b,interface_b) VALUES (?,?,?,?,?,?)", c.id(), c.name(), c.hostA(), c.interfaceA(), c.hostB(), c.interfaceB());
        return c;
    }
    private synchronized Endpoint read(String hostId, String name) {
        String key = hostId + ":" + name;
        var previous = cache.get(key);
        if (previous != null && System.currentTimeMillis() - previous.measuredAt() < 9500) return previous;
        Endpoint result;
        try {
            var h = host(hostId);
            var snapshot = mikrotik.query(h, "connection", name);
            var row = snapshot.rows().getFirst();
            result = new Endpoint(snapshot.measuredAt(), name, "true".equals(row.get("running")) && !"true".equals(row.get("disabled")), row.get("rx-bits-per-second"), row.get("tx-bits-per-second"), null);
        } catch (RuntimeException ex) {
            result = new Endpoint(System.currentTimeMillis(), name, false, null, null, "Consulta indisponível. Verifique a API e a interface do equipamento.");
        }
        if (cache.size() > 2000) cache.entrySet().removeIf(e -> System.currentTimeMillis() - e.getValue().measuredAt() > 30000);
        cache.put(key, result);
        return result;
    }
    @PostMapping("/api/connections/readings") @ResponseBody
    public List<Reading> readings(@RequestBody Set<String> ids) {
        if (ids == null || ids.size() > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return all().stream().filter(c -> ids.contains(c.id())).map(c -> new Reading(c.id(), read(c.hostA(), c.interfaceA()), read(c.hostB(), c.interfaceB()))).toList();
    }
}
