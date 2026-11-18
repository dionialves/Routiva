package br.com.scriptping.maps;

import br.com.scriptping.equipment.EquipmentProvider;
import org.springframework.core.env.Environment;
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
    private final Environment environment;
    public MappedConnectionController(JdbcTemplate jdbc, ZabbixService inventory, MikrotikService mikrotik, Environment environment) {
        this.jdbc = jdbc; this.inventory = inventory; this.mikrotik = mikrotik; this.environment = environment;
    }
    public record Connection(String id, String name, String hostA, String interfaceA, String hostB, String interfaceB) {}
    public record Endpoint(long measuredAt, String name, boolean up, String rxBps, String txBps, String error, Long capacityBps) {}
    public record Reading(String id, Endpoint a, Endpoint b) {}
    private final Map<String, Endpoint> cache = new HashMap<>();
    private record Counters(long at, java.math.BigInteger rx, java.math.BigInteger tx) {}
    private final Map<String, Counters> counters = new HashMap<>();

    static java.math.BigInteger counter(String value) {
        try {
            var number = new java.math.BigInteger(value);
            return number.signum() >= 0 && number.bitLength() <= 64 ? number : null;
        } catch (RuntimeException ex) { return null; }
    }
    static String rate(java.math.BigInteger now, java.math.BigInteger before, long elapsed) {
        if (now == null || before == null || elapsed <= 0 || elapsed > 30000 || now.compareTo(before) < 0) return null;
        return now.subtract(before).multiply(java.math.BigInteger.valueOf(8000))
            .divide(java.math.BigInteger.valueOf(elapsed)).toString();
    }
    static Long capacity(String value) {
        if (value == null) return null;
        var matcher = java.util.regex.Pattern.compile("([0-9.]+)([kMGT]?)bps", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(value.trim());
        if (!matcher.matches()) return null;
        try {
            int power = switch (matcher.group(2).toUpperCase()) { case "K" -> 1; case "M" -> 2; case "G" -> 3; case "T" -> 4; default -> 0; };
            double speed = Double.parseDouble(matcher.group(1)) * Math.pow(1000, power);
            return Double.isFinite(speed) && speed > 0 && speed <= Long.MAX_VALUE ? (long) speed : null;
        } catch (NumberFormatException ex) { return null; }
    }

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
        return host;
    }
    private EquipmentProvider.Connection apiConnection() {
        String username = environment.getProperty("MIKROTIK_USERNAME", "");
        String password = environment.getProperty("MIKROTIK_PASSWORD", "");
        boolean tls = Boolean.parseBoolean(environment.getProperty("MIKROTIK_API_TLS", "false"));
        int port;
        try {
            port = Integer.parseInt(environment.getProperty("MIKROTIK_API_PORT", tls ? "8729" : "8728"));
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "MIKROTIK_API_PORT inválida na configuração geral.");
        }
        if (username.isBlank() || password.isBlank() || port < 1 || port > 65535)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Configure MIKROTIK_USERNAME, MIKROTIK_PASSWORD e MIKROTIK_API_PORT no .env para acessar os equipamentos.");
        return new EquipmentProvider.Connection(username, password, port, tls);
    }
    @GetMapping("/api/connections/interfaces/{hostId}") @ResponseBody
    public List<Map<String, String>> interfaces(@PathVariable String hostId) {
        return mikrotik.query(host(hostId), "interfaces", null, apiConnection()).rows().stream()
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
            var snapshot = mikrotik.query(h, "connection", name, apiConnection());
            var row = snapshot.rows().getFirst();
            var sample = new Counters(snapshot.measuredAt(), counter(row.get("rx-byte")), counter(row.get("tx-byte")));
            var before = counters.put(key, sample);
            String rx = before == null ? null : rate(sample.rx(), before.rx(), sample.at() - before.at());
            String tx = before == null ? null : rate(sample.tx(), before.tx(), sample.at() - before.at());
            // Older collectors may already supply measured rates instead of counters.
            if (!row.containsKey("rx-byte") && counter(row.get("rx-bits-per-second")) != null) rx = row.get("rx-bits-per-second");
            if (!row.containsKey("tx-byte") && counter(row.get("tx-bits-per-second")) != null) tx = row.get("tx-bits-per-second");
            result = new Endpoint(snapshot.measuredAt(), name, "true".equals(row.get("running")) && !"true".equals(row.get("disabled")), rx, tx, null, capacity(row.get("rate")));
        } catch (RuntimeException ex) {
            result = new Endpoint(System.currentTimeMillis(), name, false, null, null, "Consulta indisponível. Verifique a API e a interface do equipamento.", previous == null ? null : previous.capacityBps());
            counters.remove(key);
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
