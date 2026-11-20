package br.com.scriptping.maps;

import br.com.scriptping.availability.*;
import br.com.scriptping.zabbix.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class MapHostSummaryController {
    private final NetworkMapService maps;
    private final HostAvailabilityService availability;
    private final ZabbixClient zabbix;
    private final MapPingController ping;
    private final NamedParameterJdbcTemplate jdbc;
    private final PacketLossPolicy loss;
    private final PopLatencyService latency;
    private final Map<String, Cached> cache = new HashMap<>();
    private record Cached(long at, Summary summary) {}
    public record Summary(String name, String description, String status, Integer active, Integer activeSeverity,
        HostAvailabilityService.Metrics metrics, Double p95, String latencyState, String lossState,
        List<ZabbixClient.HostIncident> incidents, Long updatedAt, String warning) {}

    public MapHostSummaryController(NetworkMapService maps, HostAvailabilityService availability,
        ZabbixClient zabbix, MapPingController ping, NamedParameterJdbcTemplate jdbc,
        PacketLossPolicy loss, PopLatencyService latency) {
        this.maps = maps; this.availability = availability; this.zabbix = zabbix;
        this.ping = ping; this.jdbc = jdbc; this.loss = loss; this.latency = latency;
    }

    @GetMapping("/api/maps/hosts/{id}/summary")
    public synchronized ResponseEntity<Summary> summary(@PathVariable String id) {
        var host = maps.inventoryHosts().stream().filter(h -> h.id().equals(id)).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        long now = System.currentTimeMillis();
        cache.entrySet().removeIf(e -> now - e.getValue().at() > 15000);
        if (cache.containsKey(id)) return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(cache.get(id).summary());
        var warnings = new ArrayList<String>();
        HostAvailabilityService.Metrics metrics = null;
        Double p95 = null;
        Long updatedAt = null;
        String status = "unknown";
        try {
            var report = availability.report(id, 24);
            metrics = report.total();
            updatedAt = report.lastSampleAt();
            // Raw samples may already have been retired. Never derive a percentile from averages
            // or present a partial raw window as the published 24-hour population.
            var values = jdbc.queryForList("""
                SELECT latency FROM ping_sample WHERE host_id=:id AND target=:target
                AND measured_at>=:from AND measured_at<:to AND latency>=0 ORDER BY latency
                """, Map.of("id", id, "target", host.ip(), "from", report.from(), "to", report.to()), Double.class)
                .stream().filter(v -> v != null && Double.isFinite(v)).toList();
            if (!values.isEmpty() && values.size() == metrics.healthy() + metrics.degraded())
                p95 = values.get((int) Math.ceil(values.size() * 0.95) - 1);
        } catch (Exception ex) { warnings.add("Indicadores indisponíveis."); }
        try {
            var states = ping.status(new NetworkMapModels.Targets(Set.of(), Set.of(id))).getBody();
            if (states != null && !states.isEmpty()) {
                status = states.getFirst().state();
                if (states.getFirst().measuredAt() != null) updatedAt = states.getFirst().measuredAt();
            }
        } catch (Exception ex) { warnings.add("Estado atual indisponível."); }
        Integer active = null;
        Integer activeSeverity = null;
        List<ZabbixClient.HostIncident> incidents = null;
        try {
            var events = zabbix.activeIncidents(List.of(id));
            if (!events.containsKey(id)) warnings.add("Contagem de incidentes indisponível.");
            else {
                active = events.get(id).size();
                activeSeverity = events.get(id).stream().map(ZabbixClient.Incident::severity).max(Integer::compareTo).orElse(null);
            }
        }
        catch (Exception ex) { warnings.add("Contagem de incidentes indisponível."); }
        try { incidents = zabbix.hostIncidentHistory(id, 5); }
        catch (Exception ex) { warnings.add("Histórico de incidentes indisponível."); }
        var result = new Summary(host.name(), null, status, active, activeSeverity, metrics, p95,
            latency.state(metrics == null ? null : metrics.latency()),
            metrics == null ? "SEM_DADOS" : loss.windowQuality(metrics.lost(), metrics.total()).name(),
            incidents, updatedAt, String.join(" ", warnings));
        cache.put(id, new Cached(now, result));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }
}
