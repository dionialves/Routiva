package br.com.scriptping.diagnostics;

import br.com.scriptping.zabbix.*;
import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Controller
public class DiagnosticsController {
    private final ZabbixService inventory;
    private final ZabbixClient zabbix;
    private final NamedParameterJdbcTemplate jdbc;

    public DiagnosticsController(ZabbixService inventory, ZabbixClient zabbix,
            NamedParameterJdbcTemplate jdbc) {
        this.inventory = inventory;
        this.zabbix = zabbix;
        this.jdbc = jdbc;
    }

    @GetMapping({"/diagnostico/grupos", "/diagnostico/grupos/{id}"})
    public String page() { return "diagnostics"; }

    private List<Host> hosts(String id) {
        var hosts = inventory.hosts().stream()
            .filter(h -> h.groups().stream().anyMatch(g -> g.id().equals(id))).toList();
        if (hosts.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Grupo não encontrado no inventário monitorado");
        return hosts;
    }

    public record Group(String id, String name, long hosts) {}
    @GetMapping("/api/diagnostics/groups")
    @ResponseBody
    public ResponseEntity<List<Group>> groups() {
        var hosts = inventory.hosts();
        var groups = hosts.stream().flatMap(h -> h.groups().stream()).distinct()
            .sorted(Comparator.comparing(Host.Group::name, String.CASE_INSENSITIVE_ORDER))
            .map(g -> new Group(g.id(), g.name(), hosts.stream().filter(h -> h.groups().contains(g)).count())).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(groups);
    }

    public record Live(String id, Long measuredAt, Double ping, Double loss, String state) {}
    @GetMapping("/api/diagnostics/groups/{id}/live")
    @ResponseBody
    public ResponseEntity<List<Live>> live(@PathVariable String id, @RequestParam(required = false) Long since) {
        var hosts = hosts(id);
        long now = System.currentTimeMillis();
        var samples = new HashMap<String, List<br.com.scriptping.ingestion.PingSample>>();
        jdbc.query("SELECT id,host_id,target,measured_at,latency,error FROM ping_sample WHERE host_id IN (:ids) AND measured_at >= :since ORDER BY measured_at DESC,id DESC",
            Map.of("ids", hosts.stream().map(Host::id).toList(), "since", since == null ? now - 60000 : Math.min(since, now)), rs -> {
                var sample = new br.com.scriptping.ingestion.PingSample(rs.getLong("id"), rs.getString("host_id"),
                    rs.getString("target"), rs.getLong("measured_at"), (Double) rs.getObject("latency"), rs.getString("error"));
                samples.computeIfAbsent(sample.hostId(), key -> new ArrayList<>()).add(sample);
            });
        var result = hosts.stream().map(h -> {
            var rows = samples.getOrDefault(h.id(), List.of()).stream().filter(s -> s.target().equals(h.ip())).toList();
            if (rows.isEmpty()) return new Live(h.id(), null, null, null, "Sem dados recentes");
            var latest = rows.getFirst();
            long valid = rows.stream().filter(s -> s.outcome() != 2).count();
            Double loss = valid == 0 ? null : 100d * rows.stream().filter(s -> s.outcome() == 1).count() / valid;
            Double ping = rows.stream().filter(s -> s.outcome() == 0 && s.latency() != null)
                .mapToDouble(s -> s.latency()).average().stream().boxed().findFirst().orElse(null);
            boolean stale = now - latest.measuredAt() > 10000;
            return new Live(h.id(), latest.measuredAt(), stale && since == null ? null : ping, stale && since == null ? null : loss,
                stale ? "Coleta atrasada" : latest.outcome() == 0 ? "Respondendo" : latest.outcome() == 1 ? "Sem resposta" : "Falha na coleta");
        }).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }

    @org.springframework.beans.factory.annotation.Value("${monitor.latency-limit-ms:20}")
    private double latencyLimit = 20;
    @org.springframework.beans.factory.annotation.Value("${monitor.loss.hour-normal-max-percent:0.33}")
    private double lossNormal = 0.33;
    @org.springframework.beans.factory.annotation.Value("${monitor.loss.hour-critical-min-percent:1.0}")
    private double lossCritical = 1.0;
    public record Limits(double latency, double lossNormal, double lossCritical) {}
    public record Details(List<Host> hosts, Map<String, Map<String, String>> equipment,
        Map<String, Integer> incidents, String warning, Limits limits) {}
    @GetMapping("/api/diagnostics/groups/{id}/details")
    @ResponseBody
    public ResponseEntity<Details> details(@PathVariable String id) {
        var hosts = hosts(id);
        var ids = hosts.stream().map(Host::id).toList();
        Map<String, Map<String, String>> equipment = Map.of();
        var incidents = new HashMap<String, Integer>();
        String warning = "";
        try { equipment = zabbix.diagnosticInventory(ids); }
        catch (Exception ex) { warning = "Inventário do equipamento indisponível. "; }
        try { zabbix.activeIncidents(ids).forEach((key, value) -> incidents.put(key, value.size())); }
        catch (Exception ex) { warning += "Incidentes indisponíveis."; }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Details(hosts, equipment, incidents, warning, new Limits(latencyLimit, lossNormal, lossCritical)));
    }
}
