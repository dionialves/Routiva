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
    public record MetricElement(String type, String id, Double latency, Double loss, String latencyState, String lossState) {}
    public record MetricResponse(long from, long to, String version, long builtAt, List<MetricElement> elements) {}

    @PostMapping("/metrics")
    public MetricResponse metrics(@RequestBody MetricRequest request) {
        if (request.groups() == null || request.hosts() == null
            || AvailabilityPeriod.OPTIONS.stream().noneMatch(p -> p.minutes() == request.minutes()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Consulta inválida");
        var result = indicators.mapMetrics(request.minutes(), request.groups(), request.hosts(), service.inventoryHosts());
        return new MetricResponse(result.from(), result.to(), result.version(), result.builtAt(), result.elements().stream().map(e -> {
            var m = e.metrics();
            String latencyState = m.latency() == null ? "SEM_DADOS" : m.latency() > 20 ? "CRITICO" : m.latency() >= 15 ? "ATENCAO" : "NORMAL";
            return new MetricElement(e.type(), e.id(), m.latency(), m.loss(), latencyState,
                lossPolicy.windowQuality(m.lost(), m.total()).name());
        }).toList());
    }

    @PostMapping
    public Saved save(@RequestBody Saved input) throws JsonProcessingException {
        return service.save(input);
    }
}
