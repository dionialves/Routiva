package br.com.scriptping.maps;

import br.com.scriptping.availability.AvailabilityPeriod;
import br.com.scriptping.availability.AvailabilitySnapshotCache;
import br.com.scriptping.availability.PacketLossPolicy;
import br.com.scriptping.zabbix.Host;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.List;
import java.util.Set;
import org.springframework.web.bind.annotation.*;

import static br.com.scriptping.maps.NetworkMapModels.*;

@RestController
@RequestMapping("/api/maps")
public class NetworkMapController {
    private final NetworkMapService service;
    private final AvailabilitySnapshotCache indicators;
    private final PacketLossPolicy lossPolicy;

    public NetworkMapController(NetworkMapService service, AvailabilitySnapshotCache indicators,
        PacketLossPolicy lossPolicy) {
        this.indicators = indicators;
        this.lossPolicy = lossPolicy;
        this.service = service;
    }

    @GetMapping
    public List<Saved> maps() {
        return service.maps();
    }

    @GetMapping("/groups")
    public List<Host.Group> groups() {
        return service.groups();
    }

    @GetMapping("/hosts")
    public List<HostOption> hosts() {
        return service.hosts();
    }

    @PostMapping("/status")
    public List<HostState> status(@RequestBody Set<String> groups) {
        return service.status(groups);
    }

    @PostMapping("/states")
    public List<HostState> states(@RequestBody Targets targets) {
        return service.states(targets);
    }

    @PostMapping("/summaries")
    public List<HostState> summaries(@RequestBody Targets targets) {
        return service.summaries(targets);
    }

    public record MetricRequest(Set<String> groups, Set<String> hosts, int minutes) {}
    public record MetricElement(String type, String id, Double latency, Double loss, String latencyState, String lossState, PopLatencyService.Summary lossSummary) {}
    public record MetricResponse(long from, long to, String version, long builtAt, List<MetricElement> elements) {}

    @PostMapping("/metrics")
    public MetricResponse metrics(@RequestBody MetricRequest request) {
        if (request.groups() == null || request.hosts() == null
            || AvailabilityPeriod.OPTIONS.stream().noneMatch(p -> p.minutes() == request.minutes()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Consulta inválida");
        var inventory = service.inventoryHosts();
        var selected = inventory.stream().filter(h -> request.hosts().contains(h.id())
            || h.groups().stream().anyMatch(g -> request.groups().contains(g.id()))).toList();
        var hostIds = new java.util.HashSet<>(request.hosts());
        selected.forEach(h -> hostIds.add(h.id()));
        var result = indicators.mapMetrics(request.minutes(), request.groups(), hostIds, inventory);
        var sampleTimes = new java.util.HashMap<String, Long>();
        result.elements().stream().filter(e -> e.type().equals("host") && e.lastSampleAt() != null)
            .forEach(e -> sampleTimes.put(e.id(), e.lastSampleAt()));
        var hostMetrics = result.elements().stream().filter(e -> e.type().equals("host"))
            .collect(java.util.stream.Collectors.toMap(AvailabilitySnapshotCache.MapMetric::id,
                AvailabilitySnapshotCache.MapMetric::metrics));
        return new MetricResponse(result.from(), result.to(), result.version(), result.builtAt(), result.elements().stream().filter(e -> !e.type().equals("host") || request.hosts().contains(e.id())).map(e -> {
            var m = e.metrics();
            String latencyState = m.latency() == null ? "SEM_DADOS" : m.latency() > 20 ? "CRITICO" : m.latency() >= 15 ? "ATENCAO" : "NORMAL";
            return new MetricElement(e.type(), e.id(), m.latency(), m.loss(), latencyState,
                lossPolicy.windowQuality(m.lost(), m.total()).name(),
                lossSummary(selected.stream().filter(h -> e.type().equals("host") ? h.id().equals(e.id())
                    : h.groups().stream().anyMatch(g -> g.id().equals(e.id()))).toList(), hostMetrics, sampleTimes));
        }).toList());
    }

    private PopLatencyService.Summary lossSummary(List<Host> hosts,
        java.util.Map<String, br.com.scriptping.availability.HostAvailabilityService.Metrics> metrics, java.util.Map<String, Long> sampleTimes) {
        var rows = hosts.stream().map(h -> {
            var m = metrics.get(h.id());
            return new PopLatencyService.Reading(h.id(), h.name(), m == null ? null : m.loss(),
                m == null ? "SEM_DADOS" : lossPolicy.windowQuality(m.lost(), m.total()).name(), sampleTimes.get(h.id()));
        }).sorted(java.util.Comparator.comparing(PopLatencyService.Reading::latency,
            java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder()))
            .thenComparing(PopLatencyService.Reading::name)).toList();
        long lost = 0, total = 0;
        for (var h : hosts) {
            var m = metrics.get(h.id());
            if (m != null) { lost += m.lost(); total += m.total(); }
        }
        Double maximum = rows.stream().map(PopLatencyService.Reading::latency)
            .filter(java.util.Objects::nonNull).max(Double::compareTo).orElse(null);
        String state = rows.stream().filter(r -> r.latency() != null).findFirst()
            .map(PopLatencyService.Reading::state).orElse("SEM_DADOS");
        return new PopLatencyService.Summary(lossPolicy.visiblePercentage(lost, total), maximum,
            (int) rows.stream().filter(r -> "ATENCAO".equals(r.state()) || "CRITICO".equals(r.state())).count(),
            state, rows.stream().map(PopLatencyService.Reading::updatedAt).filter(java.util.Objects::nonNull)
                .max(Long::compareTo).orElse(null), rows.stream().limit(3).toList());
    }

    @org.springframework.beans.factory.annotation.Autowired
    private PopLatencyService popLatency;

    @PostMapping("/latency-summaries")
    public java.util.Map<String, PopLatencyService.Summary> latencySummaries(@RequestBody Targets targets) {
        if (targets.groups() == null || targets.hosts() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Consulta inválida");
        return popLatency.summaries(targets.groups(), targets.hosts(), service.inventoryHosts());
    }

    @PostMapping
    public Saved save(@RequestBody Saved input) throws JsonProcessingException {
        return service.save(input);
    }
}
