package br.com.scriptping.accesspoint;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ap/{host}/latency")
public class LatencyAssessmentController {
    private final LatencyAssessmentService service;
    @org.springframework.beans.factory.annotation.Autowired
    public LatencyAssessmentController(LatencyAssessmentService service) { this.service = service; }
    LatencyAssessmentController(AccessPointService aps, br.com.scriptping.ixc.IxcClient ixc,
        br.com.scriptping.ingestion.ClientPingSessions pings, br.com.scriptping.ingestion.CollectorClient collector) {
        this(new LatencyAssessmentService(aps, ixc, pings, collector));
    }
    LatencyAssessmentController(AccessPointService aps, br.com.scriptping.ixc.IxcClient ixc,
        br.com.scriptping.ingestion.ClientPingSessions pings, br.com.scriptping.ingestion.CollectorClient collector, java.time.Clock clock) {
        this(new LatencyAssessmentService(aps, ixc, pings, collector, clock));
    }
    @GetMapping public ResponseEntity<?> prepare(@PathVariable String host) { return fresh(service.targets(host)); }
    @PostMapping public ResponseEntity<?> startVisit(@PathVariable String host,
        @RequestParam(defaultValue="60") int packets, HttpSession owner) { return start(host, packets, owner); }
    public ResponseEntity<?> start(String host, HttpSession owner) { return start(host, 60, owner); }
    public ResponseEntity<?> start(String host, int packets, HttpSession owner) { return fresh(service.start(host, packets, owner.getId())); }
    @GetMapping("/{id}") public ResponseEntity<?> read(@PathVariable String host, @PathVariable String id, HttpSession owner) {
        return fresh(service.read(host, id, owner.getId()));
    }
    @DeleteMapping("/{id}") public ResponseEntity<?> stop(@PathVariable String host, @PathVariable String id, HttpSession owner) {
        service.stop(host, id, owner.getId()); return ResponseEntity.noContent().build();
    }
    void cleanup() { service.cleanup(); }
    private ResponseEntity<?> fresh(Object body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore())
        .headers(headers -> headers.setDate(service.now())).body(body); }
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<?> error(org.springframework.web.server.ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(java.util.Map.of("message",
            java.util.Objects.requireNonNullElse(error.getReason(), "Avaliação indisponível.")));
    }
}
