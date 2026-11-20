package br.com.scriptping.maps;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Controller
public class MappedConnectionController {
    private final JdbcTemplate jdbc;
    private final MapZabbixReadings zabbixReadings;
    private final br.com.scriptping.hosts.HostRepository hosts;
    public MappedConnectionController(JdbcTemplate jdbc, br.com.scriptping.hosts.HostRepository hosts, MapZabbixReadings zabbixReadings) {
        this.jdbc = jdbc; this.hosts = hosts; this.zabbixReadings = zabbixReadings;
    }
    public record Connection(String id, String name, String hostA, String interfaceA, String hostB, String interfaceB) {}
    public record Endpoint(long measuredAt, String name, boolean up, String rxBps, String txBps, String error,
                           Long capacityBps, long validUntil, long rxValidUntil, long txValidUntil,
                           String trafficError, long queriedAt, String queryError) {}
    public record Reading(String id, Endpoint a, Endpoint b, String hostA, String hostB) {}

    @ModelAttribute
    public void noCache(jakarta.servlet.http.HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }

    @ExceptionHandler(ResponseStatusException.class) @ResponseBody
    public org.springframework.http.ResponseEntity<Map<String, String>> error(ResponseStatusException ex) {
        return org.springframework.http.ResponseEntity.status(ex.getStatusCode())
            .body(Map.of("message", ex.getReason() == null ? "Consulta indisponível." : ex.getReason()));
    }
    @GetMapping("/api/maps/connections/discover/{hostId}") @ResponseBody
    public List<Map<String, String>> discover(@PathVariable String hostId) {
        hosts.target(hostId);
        return zabbixReadings.discover(hostId);
    }
    @GetMapping("/api/maps/connections/interfaces/{hostId}") @ResponseBody
    public List<Map<String, String>> interfaces(@PathVariable String hostId) {
        return discover(hostId);
    }
    @PostMapping("/api/maps/connections/validate") @ResponseBody
    public Connection validate(@RequestBody Connection input) {
        if (input.name() == null || input.name().isBlank() || input.name().length() > 120
            || input.hostA() == null || input.hostB() == null || input.hostA().equals(input.hostB()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe nome e dois equipamentos diferentes.");
        if (input.interfaceA() == null || input.interfaceA().isBlank() || input.interfaceA().length() > 128
            || input.interfaceB() == null || input.interfaceB().isBlank() || input.interfaceB().length() > 128)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Selecione as interfaces cadastradas nos elementos do mapa.");
        var c = new Connection(UUID.randomUUID().toString(), input.name().trim(), input.hostA(), input.interfaceA(), input.hostB(), input.interfaceB());
        return c;
    }
    public record Batch(String version, boolean unchanged, List<Reading> readings) {}
    @PostMapping("/api/maps/{mapId}/connections/readings") @ResponseBody
    public Batch snapshot(@PathVariable String mapId, @RequestBody Set<String> ids,
                          @RequestParam(defaultValue = "false") boolean force,
                          @RequestHeader(value = "X-Map-Readings-Version", defaultValue = "") String previousVersion) {
        var readings = readings(mapId, ids, force);
        try {
            var bytes = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(readings);
            String version = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
            boolean unchanged = version.equals(previousVersion);
            return new Batch(version, unchanged, unchanged ? List.of() : readings);
        } catch (java.security.NoSuchAlgorithmException | com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }
    public List<Reading> readings(String mapId, Set<String> ids, boolean force) {
        if (ids == null || ids.size() > 1000 || ids.stream().anyMatch(Objects::isNull)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var layouts = jdbc.queryForList("SELECT layout FROM network_map WHERE id=?", String.class, mapId);
        if (layouts.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        try {
            var layout = new com.fasterxml.jackson.databind.ObjectMapper().readValue(layouts.getFirst(), NetworkMapModels.Layout.class);
            var definitions = layout.links().stream().map(NetworkMapModels.Link::connection).filter(Objects::nonNull)
                .filter(c -> ids.contains(c.id())).distinct().toList();
            var keys = new HashSet<MapZabbixReadings.Key>();
            definitions.forEach(c -> { keys.add(new MapZabbixReadings.Key(c.hostA(), c.interfaceA()));
                keys.add(new MapZabbixReadings.Key(c.hostB(), c.interfaceB())); });
            var values = zabbixReadings.read(keys, force);
            return definitions.stream().map(c -> new Reading(c.id(), values.get(new MapZabbixReadings.Key(c.hostA(), c.interfaceA())),
                values.get(new MapZabbixReadings.Key(c.hostB(), c.interfaceB())), c.hostA(), c.hostB())).toList();
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException(ex); }
    }
}
