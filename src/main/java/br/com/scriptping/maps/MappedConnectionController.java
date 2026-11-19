package br.com.scriptping.maps;

import br.com.scriptping.equipment.EquipmentProvider;
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
    private final br.com.scriptping.datacom.DatacomEquipmentProvider datacom;
    private final br.com.scriptping.mimosa.MimosaEquipmentProvider mimosa;
    private final ZabbixService inventory;
    private final MikrotikService mikrotik;
    private final br.com.scriptping.hosts.HostConnections connections;
    private final br.com.scriptping.hosts.HostRepository hosts;
    public MappedConnectionController(JdbcTemplate jdbc, ZabbixService inventory, MikrotikService mikrotik, br.com.scriptping.hosts.HostConnections connections, br.com.scriptping.hosts.HostRepository hosts, br.com.scriptping.datacom.DatacomEquipmentProvider datacom, br.com.scriptping.mimosa.MimosaEquipmentProvider mimosa) {
        this.jdbc = jdbc; this.inventory = inventory; this.mikrotik = mikrotik; this.connections = connections; this.hosts = hosts; this.datacom = datacom; this.mimosa = mimosa;
    }
    public record Connection(String id, String name, String hostA, String interfaceA, String hostB, String interfaceB) {}
    public record Endpoint(long measuredAt, String name, boolean up, String rxBps, String txBps, String error, Long capacityBps) {}
    public record Reading(String id, Endpoint a, Endpoint b, String hostA, String hostB) {}
    private record Source(String address, br.com.scriptping.hosts.HostRepository.Credentials credentials) {}
    private record Counters(long at, java.math.BigInteger rx, java.math.BigInteger tx, String generation) {}
    private static final class InterfaceState {
        final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();
        Endpoint endpoint;
        Source source;
        Counters counters;
    }
    private final Map<String, InterfaceState> readingsByInterface = new java.util.concurrent.ConcurrentHashMap<>();

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

    private Host host(String id) {
        var host = inventory.hosts().stream().filter(h -> h.id().equals(id)).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Equipamento não encontrado."));
        return hosts.target(id);
    }
    private EquipmentProvider.Connection apiConnection(String id) {
        if (!Set.of("MikroTik", "Datacom", "Mimosa").contains(hosts.credentials(id) == null ? "" : hosts.credentials(id).manufacturer()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Não foi possível consultar a conexão. Verifique a autenticação do host e a compatibilidade do equipamento.");
        return connections.require(host(id));
    }
    @ExceptionHandler(ResponseStatusException.class) @ResponseBody
    public org.springframework.http.ResponseEntity<Map<String, String>> error(ResponseStatusException ex) {
        return org.springframework.http.ResponseEntity.status(ex.getStatusCode())
            .body(Map.of("message", ex.getReason() == null ? "Consulta indisponível." : ex.getReason()));
    }
    @GetMapping("/api/maps/connections/discover/{hostId}") @ResponseBody
    public List<Map<String, String>> discover(@PathVariable String hostId) {
        var target = hosts.target(hostId);
        var credentials = apiConnectionFor(hostId);
        return query(target, "discover", null, credentials).rows();
    }
    private EquipmentProvider.Connection apiConnectionFor(String hostId) {
        var saved = hosts.credentials(hostId);
        if (saved == null || !Set.of("MikroTik", "Datacom", "Mimosa").contains(saved.manufacturer()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Não foi possível carregar as interfaces. Verifique a autenticação do host e a compatibilidade do equipamento.");
        return connections.require(hosts.target(hostId));
    }
    private EquipmentProvider.Snapshot query(Host host, String section, String name, EquipmentProvider.Connection credentials) {
        if ("Mimosa".equals(hosts.credentials(host.id()).manufacturer())) {
            return mimosa.query(host, section, name, credentials);
        }
        if ("Datacom".equals(hosts.credentials(host.id()).manufacturer())) {
            return datacom.query(host, section.equals("interfaces") ? "discover" : section, name, credentials);
        }
        var result = mikrotik.query(host, section, name, credentials);
        return new EquipmentProvider.Snapshot(result.measuredAt(), result.rows(), result.warning());
    }
    @GetMapping("/api/maps/connections/interfaces/{hostId}") @ResponseBody
    public List<Map<String, String>> interfaces(@PathVariable String hostId) {
        var credentials = apiConnectionFor(hostId);
        var target = host(hostId);
        return query(target, "interfaces", null, credentials).rows().stream()
            .filter(row -> Set.of("ether", "wireless").contains(Objects.toString(row.get("type"), ""))).toList();
    }
    @PostMapping("/api/maps/connections/validate") @ResponseBody
    public Connection validate(@RequestBody Connection input) {
        if (input.name() == null || input.name().isBlank() || input.name().length() > 120
            || input.hostA() == null || input.hostB() == null || input.hostA().equals(input.hostB()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe nome e dois equipamentos diferentes.");
        for (var endpoint : List.of(new String[]{input.hostA(), Objects.toString(input.interfaceA(), "")}, new String[]{input.hostB(), Objects.toString(input.interfaceB(), "")})) {
            if (interfaces(endpoint[0]).stream().noneMatch(i -> endpoint[1].equals(i.get("name"))))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Interface não encontrada.");
        }
        var c = new Connection(UUID.randomUUID().toString(), input.name().trim(), input.hostA(), input.interfaceA(), input.hostB(), input.interfaceB());
        return c;
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 60000)
    public void expireReadings() {
        readingsByInterface.forEach((key, state) -> {
            if (!state.lock.tryLock()) return;
            try {
                if (state.endpoint != null && System.currentTimeMillis() - state.endpoint.measuredAt() > 60000)
                    readingsByInterface.remove(key, state);
            } finally { state.lock.unlock(); }
        });
    }
    private Endpoint read(String hostId, String name) {
        String key = hostId + ":" + name;
        while (true) {
            var state = readingsByInterface.computeIfAbsent(key, ignored -> new InterfaceState());
            // Network I/O holds only this interface's lock, outside the shared map.
            state.lock.lock();
            try {
                if (readingsByInterface.get(key) != state) continue;
                state.endpoint = readInterface(hostId, name, state);
                return state.endpoint;
            } finally { state.lock.unlock(); }
        }
    }
    private Endpoint readInterface(String hostId, String name, InterfaceState state) {
        var previous = state.endpoint;
        Endpoint result;
        try {
            var h = host(hostId);
            var credentials = apiConnection(hostId);
            var source = new Source(h.ip(), hosts.credentials(hostId));
            // A first counter sample has no rate yet: allow the startup follow-up after 1s.
            var baseline = state.counters;
            boolean warmingUp = previous != null && previous.error() == null && previous.up()
                && (previous.rxBps() == null || previous.txBps() == null) && baseline != null
                && baseline.rx() != null && baseline.tx() != null;
            long cacheMillis = warmingUp ? 1000 : 9500;
            if (previous != null && Objects.equals(state.source, source)
                    && System.currentTimeMillis() - previous.measuredAt() < cacheMillis) return previous;
            if (!Objects.equals(state.source, source)) state.counters = null;
            state.source = source;
            var snapshot = query(h, "connection", name, credentials);
            var row = snapshot.rows().getFirst();
            if (!Set.of("true", "false").contains(Objects.toString(row.get("running"), "")))
                throw new IllegalArgumentException("Unknown operational status");
            var sample = new Counters(snapshot.measuredAt(), counter(row.get("rx-byte")), counter(row.get("tx-byte")), row.get("counter-generation"));
            var before = state.counters;
            state.counters = sample;
            if (before != null && !Objects.equals(before.generation(), sample.generation())) before = null;
            String rx = before == null ? null : rate(sample.rx(), before.rx(), sample.at() - before.at());
            String tx = before == null ? null : rate(sample.tx(), before.tx(), sample.at() - before.at());
            // Older collectors may already supply measured rates instead of counters.
            if (!row.containsKey("rx-byte") && counter(row.get("rx-bits-per-second")) != null) rx = row.get("rx-bits-per-second");
            if (!row.containsKey("tx-byte") && counter(row.get("tx-bits-per-second")) != null) tx = row.get("tx-bits-per-second");
            result = new Endpoint(snapshot.measuredAt(), name, "true".equals(row.get("running")) && !"true".equals(row.get("disabled")), rx, tx, null, capacity(row.get("rate")));
        } catch (RuntimeException ex) {
            result = new Endpoint(System.currentTimeMillis(), name, false, null, null, "Consulta indisponível. Verifique a conexão e a interface do equipamento.", previous == null ? null : previous.capacityBps());
            state.counters = null;
        }
        return result;
    }
    @PostMapping("/api/maps/{mapId}/connections/readings") @ResponseBody
    public List<Reading> readings(@PathVariable String mapId, @RequestBody Set<String> ids) {
        if (ids == null || ids.size() > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var layouts = jdbc.queryForList("SELECT layout FROM network_map WHERE id=?", String.class, mapId);
        if (layouts.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        try {
            var layout = new com.fasterxml.jackson.databind.ObjectMapper().readValue(layouts.getFirst(), NetworkMapModels.Layout.class);
            return layout.links().stream().map(NetworkMapModels.Link::connection).filter(Objects::nonNull)
                .filter(c -> ids.contains(c.id())).map(c -> new Reading(c.id(), read(c.hostA(), c.interfaceA()), read(c.hostB(), c.interfaceB()), c.hostA(), c.hostB())).toList();
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException(ex); }
    }
}
