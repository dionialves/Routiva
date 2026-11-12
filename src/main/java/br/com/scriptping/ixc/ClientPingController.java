package br.com.scriptping.ixc;

import br.com.scriptping.ingestion.ClientPingSessions;
import br.com.scriptping.ingestion.CollectorClient;
import jakarta.servlet.http.HttpSession;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/hosts/{host}/wireless/ping")
public class ClientPingController {
    private final IxcController ixc;
    private final ClientPingSessions sessions;
    private final CollectorClient collector;
    public ClientPingController(IxcController ixc, ClientPingSessions sessions, CollectorClient collector) {
        this.ixc = ixc; this.sessions = sessions; this.collector = collector;
    }
    @PostMapping
    public ResponseEntity<ClientPingSessions.View> start(@PathVariable String host, @RequestParam String mac, HttpSession owner) {
        if (!collector.enabled()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Coletor desabilitado.");
        // Reuse the IXC lookup and validation that this MAC belongs to the selected AP.
        var logins = ixc.login(host, mac).getBody();
        var ips = logins.stream().map(login -> login.ip.strip())
            .filter(ClientPingSessions::isIpv4).distinct().toList();
        if (ips.size() != 1) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
            ips.isEmpty() ? "IPv4 válido não encontrado no IXC." : "Mais de um IPv4 encontrado no IXC para este MAC.");
        var view = sessions.start(host, owner.getId(), ips.getFirst());
        collector.refreshClientPings();
        return response(view);
    }
    @GetMapping("/{session}")
    public ResponseEntity<ClientPingSessions.View> read(@PathVariable String host, @PathVariable String session, HttpSession owner) {
        return response(sessions.read(session, host, owner.getId()));
    }
    @DeleteMapping("/{session}")
    public ResponseEntity<Void> stop(@PathVariable String host, @PathVariable String session, HttpSession owner) {
        sessions.stop(session, host, owner.getId());
        collector.refreshClientPings();
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
    private ResponseEntity<ClientPingSessions.View> response(ClientPingSessions.View view) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(view);
    }
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> error(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).cacheControl(CacheControl.noStore())
            .body(Map.of("message", ex.getReason() == null ? "Ping indisponível." : ex.getReason()));
    }
}
