package br.com.scriptping.accesspoint;

import br.com.scriptping.ingestion.ClientPingSessions;
import br.com.scriptping.ingestion.CollectorClient;
import br.com.scriptping.ixc.IxcClient;
import jakarta.servlet.http.HttpSession;
import java.util.*;
import java.time.Clock;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/ap/{host}/latency")
public class LatencyAssessmentController {
    private final AccessPointService aps;
    private final IxcClient ixc;
    private final ClientPingSessions pings;
    private final CollectorClient collector;
    private final Map<String, Assessment> assessments = new HashMap<>();
    private final Map<String, View> finalViews = new HashMap<>();
    private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private br.com.scriptping.client.radio.RadioVisitService radio;
    private final Map<String, String> radioVisits = new HashMap<>();
    public record Target(String name, String mac, String ip, String warning) {}
    public record Result(Target target, List<ClientPingSessions.Point> samples) {}
    public record View(String id, long startedAt, long endsAt, List<Result> results, int packetCount, boolean completed, boolean failed) {}
    private record Assessment(String id, String host, String owner, long start, long end,
        List<Target> targets, List<String> probes, int packets) {}

    @org.springframework.beans.factory.annotation.Autowired
    public LatencyAssessmentController(AccessPointService aps, IxcClient ixc,
        ClientPingSessions pings, CollectorClient collector) {
        this(aps, ixc, pings, collector, Clock.systemUTC());
    }
    LatencyAssessmentController(AccessPointService aps, IxcClient ixc,
        ClientPingSessions pings, CollectorClient collector, Clock clock) {
        this.aps = aps; this.ixc = ixc; this.pings = pings; this.collector = collector;
        this.clock = clock;
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 1000)
    public synchronized void cleanup() {
        long now = clock.millis();
        for (var assessment : List.copyOf(assessments.values())) {
            if (!finalViews.containsKey(assessment.id) && expiresAt(assessment) > now) view(assessment);
        }
        assessments.values().removeIf(a -> expiresAt(a) <= now);
        radioVisits.keySet().retainAll(assessments.keySet());
        finalViews.keySet().retainAll(assessments.keySet());
    }
    @GetMapping
    public ResponseEntity<?> prepare(@PathVariable String host) { return fresh(targets(host)); }

    private List<Target> targets(String host) {
        var ap = aps.host(host);
        var snapshot = aps.read(host, "wireless", null);
        if (snapshot.warning() != null && !snapshot.warning().isBlank())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, snapshot.warning());
        var macs = new LinkedHashSet<String>();
        var unidentified = new ArrayList<Target>();
        for (var row : snapshot.rows()) {
            if ("disconnected".equals(row.get("connection-status"))) continue;
            try { macs.add(IxcClient.normalizeMac(row.get("mac-address"))); }
            catch (ResponseStatusException ignored) {
                unidentified.add(new Target("Cliente wireless", Objects.requireNonNullElse(row.get("mac-address"), ""),
                    "", "MAC ausente ou inválido; não foi possível consultar o IP."));
            }
        }
        if (macs.size() + unidentified.size() > 255) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
            "A avaliação suporta até 255 clientes por AP.");
        List<IxcClient.Login> logins = List.of();
        String warning = null;
        try { logins = ixc.findByMacs(macs); }
        catch (ResponseStatusException error) { warning = "IP indisponível: consulta ao IXC falhou."; }
        var result = new ArrayList<Target>();
        result.add(new Target(ap.name() + " (AP)", "", ap.ip(),
            ClientPingSessions.isIpv4(ap.ip()) ? null : "AP sem IPv4 válido."));
        for (String mac : macs) {
            var matches = logins.stream().filter(l -> mac.equals(l.mac)).toList();
            var ips = matches.stream().map(l -> l.ip.strip()).filter(ClientPingSessions::isIpv4).distinct().toList();
            String name = matches.stream().map(l -> l.pppoe).filter(n -> !n.isBlank()).findFirst().orElse(mac);
            result.add(new Target(name, mac, ips.size() == 1 ? ips.getFirst() : "",
                ips.size() == 1 ? null : warning != null ? warning : ips.isEmpty()
                    ? "Sem IPv4 válido no IXC." : "Mais de um IPv4 no IXC."));
        }
        result.addAll(unidentified);
        return result;
    }
    public ResponseEntity<?> start(String host, HttpSession owner) { return start(host, 60, owner); }
    @PostMapping
    public synchronized ResponseEntity<?> startVisit(@PathVariable String host,
        @RequestParam(defaultValue = "60") int packets,
        @RequestHeader(value = "X-Radio-Visit", required = false) String visit, HttpSession owner) {
        if (visit != null && radio != null) radio.owned(visit, host, owner.getId());
        var response = start(host, packets, owner);
        if (visit != null && response.getBody() instanceof View result) {
            radioVisits.put(result.id(), visit);
            if (result.completed() || result.failed()) capture(assessments.get(result.id()), result);
        }
        return response;
    }
    public synchronized ResponseEntity<?> start(@PathVariable String host,
        @RequestParam(defaultValue = "60") int packets, HttpSession owner) {
        if (!Set.of(5, 10, 30, 60).contains(packets))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Selecione 5, 10, 30 ou 60 pacotes.");
        long now = clock.millis();
        assessments.values().removeIf(a -> expiresAt(a) <= now);
        finalViews.keySet().retainAll(assessments.keySet());
        if (!collector.enabled()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Coletor desabilitado.");
        if (assessments.values().stream().anyMatch(a -> a.host.equals(host) && !finalViews.containsKey(a.id) && a.end > now))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Já existe uma avaliação em andamento neste AP.");
        var targets = targets(host);
        if (targets.getFirst().warning() != null)
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, targets.getFirst().warning());
        long start = clock.millis(), end = start + packets * 1000L + 30000;
        var probes = new ArrayList<String>();
        try {
            for (var target : targets) probes.add(target.warning() == null
                ? pings.startPackets(host, owner.getId(), target.ip(), packets, end).id() : "");
        } catch (RuntimeException error) {
            probes.stream().filter(p -> !p.isEmpty()).forEach(p -> pings.stop(p, host, owner.getId()));
            collector.refreshClientPings();
            throw error;
        }
        var assessment = new Assessment(UUID.randomUUID().toString(), host, owner.getId(), start, end, targets, probes, packets);
        assessments.put(assessment.id, assessment);
        collector.refreshClientPings();
        return fresh(view(assessment));
    }
    @GetMapping("/{id}")
    public synchronized ResponseEntity<?> read(@PathVariable String host, @PathVariable String id, HttpSession owner) {
        return fresh(view(owned(host, id, owner)));
    }
    @DeleteMapping("/{id}")
    public synchronized ResponseEntity<?> stop(@PathVariable String host, @PathVariable String id, HttpSession owner) {
        var assessment = owned(host, id, owner);
        view(assessment);
        assessment.probes.stream().filter(p -> !p.isEmpty()).forEach(p -> pings.stop(p, host, owner.getId()));
        assessments.remove(id);
        finalViews.remove(id);
        collector.refreshClientPings();
        return ResponseEntity.noContent().build();
    }
    private Assessment owned(String host, String id, HttpSession owner) {
        var assessment = assessments.get(id);
        if (assessment == null || !assessment.host.equals(host) || !assessment.owner.equals(owner.getId())
            || expiresAt(assessment) <= clock.millis())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Avaliação encerrada ou expirada.");
        return assessment;
    }
    private long expiresAt(Assessment a) {
        var finalView = finalViews.get(a.id);
        return (finalView == null ? a.end : finalView.endsAt()) + 300000;
    }
    private View view(Assessment a) {
        var completed = finalViews.get(a.id);
        if (completed != null) return completed;
        var results = new ArrayList<Result>();
        for (int i = 0; i < a.targets.size(); i++) results.add(new Result(a.targets.get(i),
            a.probes.get(i).isEmpty() ? List.of() : pings.read(a.probes.get(i), a.host, a.owner).samples()));
        boolean complete = true;
        for (var result : results) {
            if (result.target.warning() == null && result.samples.size() < a.packets) complete = false;
        }
        boolean failed = !complete && clock.millis() >= a.end;
        var view = new View(a.id, a.start, complete || failed ? clock.millis() : a.end,
            List.copyOf(results), a.packets, complete, failed);
        if (complete || failed) {
            capture(a, view);
            finalViews.put(a.id, view);
        }
        return view;
    }
    private void capture(Assessment a, View view) {
        String visit = radioVisits.get(a.id);
        if (radio == null || visit == null) return;
        for (var result : view.results()) {
            if (result.target().mac().isBlank() || result.target().warning() != null) continue;
            var measured = result.samples().stream().filter(p -> p.loss() != null).toList();
            if (measured.isEmpty()) continue;
            var replies = measured.stream().filter(p -> p.latency() != null).toList();
            long lost = measured.stream().filter(p -> p.loss() > 0).count();
            var stats = new LinkedHashMap<String, Object>();
            stats.put("latency", replies.isEmpty() ? null : replies.stream().mapToDouble(p -> p.latency()).average().orElseThrow());
            stats.put("loss", lost * 100d / measured.size());
            stats.put("replies", replies.size()); stats.put("lost", lost); stats.put("total", measured.size());
            stats.put("ip", result.target().ip()); stats.put("startedAt", a.start);
            radio.latency(visit, a.host, a.owner, result.target().mac(), view.endsAt(), stats);
        }
    }
    private ResponseEntity<?> fresh(Object body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore())
        .headers(headers -> headers.setDate(clock.millis())).body(body); }
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> error(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("message", Objects.requireNonNullElse(error.getReason(), "Avaliação indisponível.")));
    }
}
