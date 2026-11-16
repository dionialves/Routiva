package br.com.scriptping.client.radio;

import jakarta.servlet.http.HttpSession;
import java.security.Principal;
import java.util.*;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

@Controller
public class RadioClientController {
    private final RadioClientService clients;
    private final RadioVisitService visits;
    public RadioClientController(RadioClientService clients, RadioVisitService visits) {
        this.clients = clients; this.visits = visits;
    }
    @GetMapping("/clientes/radio") public String page() { return "radio-clients"; }
    @GetMapping("/api/clients/radio") @ResponseBody
    public Object list() { return clients.list(); }
    @GetMapping("/api/clients/radio/{id}/history") @ResponseBody
    public Object history(@PathVariable String id, @RequestParam(defaultValue = "0") int page) { return org.springframework.http.ResponseEntity.ok().header("X-Total-Count", Long.toString(clients.historyCount(id))).body(clients.history(id, page)); }
    public record Binding(String login, long from, boolean confirm) {}
    @PostMapping("/api/clients/radio/{id}/binding") @ResponseBody
    public Object bind(@PathVariable String id, @RequestBody Binding request, Principal operator) {
        return clients.manual(id, request.login(), request.from(), operator.getName(), request.confirm());
    }
    @PostMapping("/api/ap/{host}/radio-visits/{visit}/close") @ResponseBody
    public Object close(@PathVariable String host, @PathVariable String visit, HttpSession owner) {
        if (!clients.visibleHosts().contains(host))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN, "Sem acesso ao AP.");
        visits.close(visit, host, owner.getId()); return org.springframework.http.ResponseEntity.accepted().body(Map.of("closed", true));
    }
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class) @ResponseBody
    public org.springframework.http.ResponseEntity<?> error(org.springframework.web.server.ResponseStatusException error) {
        return org.springframework.http.ResponseEntity.status(error.getStatusCode())
            .body(Map.of("message", Objects.requireNonNullElse(error.getReason(), "Operação indisponível.")));
    }
}

