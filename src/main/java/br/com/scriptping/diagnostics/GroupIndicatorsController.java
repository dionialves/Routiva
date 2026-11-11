package br.com.scriptping.diagnostics;

import br.com.scriptping.availability.*;
import br.com.scriptping.zabbix.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class GroupIndicatorsController {
    private final AvailabilitySnapshotCache cache;
    private final ZabbixService inventory;
    private final ZabbixClient zabbix;
    private final int criticalSeverity;
    private final int warningSeverity;

    public GroupIndicatorsController(AvailabilitySnapshotCache cache, ZabbixService inventory, ZabbixClient zabbix,
        @Value("${diagnostics.groups.incident-critical-severity:4}") int criticalSeverity,
        @Value("${diagnostics.groups.incident-warning-severity:2}") int warningSeverity) {
        this.cache = cache;
        this.inventory = inventory;
        this.zabbix = zabbix;
        this.criticalSeverity = criticalSeverity;
        this.warningSeverity = warningSeverity;
    }

    public record Row(String id, String name, int hosts, HostAvailabilityService.Metrics metrics,
        long observedMillis, Integer incidents, Map<Integer, Long> severities, String status) {}
    public record Page(List<Row> rows, int number, int pages, int total, long from, long to, String warning) {}

    @GetMapping("/api/diagnostics/group-indicators")
    public ResponseEntity<Page> indicators(@RequestParam(defaultValue = "1440") int minutes,
        @RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "") String status,
        @RequestParam(defaultValue = "name") String sort, @RequestParam(defaultValue = "asc") String direction,
        @RequestParam(defaultValue = "1") int page) {
        try { AvailabilityPeriod.of(minutes); }
        catch (IllegalArgumentException e) { throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "Período inválido"); }
        var hosts = List.copyOf(inventory.hosts());
        var totals = cache.groupTotals(minutes, hosts);
        Map<String, List<ZabbixClient.Incident>> incidents = Map.of();
        boolean known = true;
        try { incidents = zabbix.activeIncidents(hosts.stream().map(Host::id).toList()); }
        catch (RuntimeException e) { known = false; }
        var byGroup = new HashMap<String, Map<String, ZabbixClient.Incident>>();
        var unavailable = new HashSet<String>();
        for (var host : hosts) {
            for (var group : host.groups()) {
                if (!incidents.containsKey(host.id())) unavailable.add(group.id());
                var events = byGroup.computeIfAbsent(group.id(), key -> new HashMap<>());
                for (var event : incidents.getOrDefault(host.id(), List.of())) {
                    // Production Zabbix events carry eventid; an absent ID cannot be deduplicated safely.
                    if (event.eventId().isBlank()) { unavailable.add(group.id()); continue; }
                    events.put(event.eventId(), event);
                }
            }
        }
        var rows = new ArrayList<Row>();
        for (var group : totals.groups()) {
            var events = byGroup.getOrDefault(group.id(), Map.of()).values();
            var severities = new TreeMap<Integer, Long>();
            events.forEach(event -> severities.merge(event.severity(), 1L, Long::sum));
            int severity = events.stream().mapToInt(ZabbixClient.Incident::severity).max().orElse(-1);
            // Priority: active critical > historical critical > active warning > historical warning > no data > normal.
            String state = severity >= criticalSeverity || group.status().equals("CRITICO") ? "CRITICO"
                : severity >= warningSeverity || group.status().equals("DEGRADADO") ? "DEGRADADO" : group.status();
            rows.add(new Row(group.id(), group.name(), group.hosts(), group.metrics(), group.observedMillis(),
                known && !unavailable.contains(group.id()) ? events.size() : null, severities, state));
        }
        String query = normalize(q.strip());
        var selected = rows.stream().filter(r -> normalize(r.name() + " " + r.id()).contains(query))
            .filter(r -> status.isEmpty() || r.status().equals(status)).sorted(order(sort, direction.equals("desc"))).toList();
        int pages = Math.max(1, (selected.size() + 19) / 20), number = Math.max(1, Math.min(page, pages));
        String warning = !known || !unavailable.isEmpty() ? "Incidentes indisponíveis ou incompletos. O estado pode não refletir todos os problemas ativos." : "";
        if (totals.version().isEmpty()) warning += " Indicadores aguardando consolidação.";
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Page(
            selected.subList((number - 1) * 20, Math.min(number * 20, selected.size())), number, pages,
            selected.size(), totals.from(), totals.to(), warning.strip()));
    }

    private static String normalize(String value) {
        return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }
    private static Comparator<Row> order(String sort, boolean descending) {
        Comparator<Row> comparator;
        if (Set.of("hosts", "operational", "technical", "loss", "latency", "incidents").contains(sort)) {
            Comparator<Double> values = descending ? Comparator.reverseOrder() : Comparator.naturalOrder();
            comparator = Comparator.comparing(r -> switch (sort) {
                case "hosts" -> (double) r.hosts();
                case "operational" -> r.metrics().operational();
                case "technical" -> r.metrics().technical();
                case "loss" -> r.metrics().loss();
                case "latency" -> r.metrics().latency();
                default -> r.incidents() == null ? null : r.incidents().doubleValue();
            }, Comparator.nullsLast(values));
        } else {
            comparator = Comparator.comparing(r -> normalize(r.name()));
            if (descending) comparator = comparator.reversed();
        }
        return comparator.thenComparing(Row::id);
    }
}
