package br.com.scriptping.client.radio;

import java.security.Principal;
import java.util.*;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

@Controller
public class RadioClientController {
    private final RadioClientService clients;
    public RadioClientController(RadioClientService clients) { this.clients = clients; }
    @GetMapping("/clientes/radio") public String page() { return "radio-clients"; }
    @GetMapping("/api/clients/radio") @ResponseBody
    public Object list() { return clients.list(); }
    @GetMapping("/api/clients/radio/{id}/history") @ResponseBody
    public Object history(@PathVariable String id, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "observed") String sort,
            @RequestParam(defaultValue = "descending") String direction) {
        return org.springframework.http.ResponseEntity.ok().header("X-Total-Count", Long.toString(clients.historyCount(id)))
            .body(clients.history(id, page, sort, "descending".equals(direction)));
    }
    public record Binding(String login, long from, boolean confirm) {}
    @PostMapping("/api/clients/radio/{id}/binding") @ResponseBody
    public Object bind(@PathVariable String id, @RequestBody Binding request, Principal operator) {
        return clients.manual(id, request.login(), request.from(), operator.getName(), request.confirm());
    }
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class) @ResponseBody
    public org.springframework.http.ResponseEntity<?> error(org.springframework.web.server.ResponseStatusException error) {
        return org.springframework.http.ResponseEntity.status(error.getStatusCode())
            .body(Map.of("message", Objects.requireNonNullElse(error.getReason(), "Operação indisponível.")));
    }
}

