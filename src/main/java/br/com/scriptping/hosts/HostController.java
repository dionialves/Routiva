package br.com.scriptping.hosts;

import br.com.scriptping.zabbix.ZabbixClient;
import java.util.*;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Controller
public class HostController {
    private final ZabbixClient zabbix;
    public HostController(ZabbixClient zabbix) { this.zabbix = zabbix; }
    @GetMapping("/configuracao/hosts") public String page() { return "hosts"; }
    @GetMapping("/api/host-configurations") @ResponseBody
    public List<ManagedHost> list() { return zabbix.managedHosts(); }
    @GetMapping("/api/host-configurations/options") @ResponseBody
    public Map<String, ?> options() {
        return Map.of("groups", zabbix.groups(), "templates", zabbix.hostTemplates());
    }
    @PostMapping("/api/host-configurations") @ResponseBody
    public Map<String, String> create(@RequestBody ManagedHost.Input input) {
        return Map.of("id", zabbix.saveManagedHost(null, input));
    }
    @PutMapping("/api/host-configurations/{id}") @ResponseBody
    public Map<String, String> update(@PathVariable String id, @RequestBody ManagedHost.Input input) {
        return Map.of("id", zabbix.saveManagedHost(id, input));
    }
    public record DeleteInput(String revision, boolean confirmed) {}
    @DeleteMapping("/api/host-configurations/{id}") @ResponseBody
    public Map<String, String> delete(@PathVariable String id, @RequestBody DeleteInput input) {
        if (!input.confirmed()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Confirme a exclusão do host.");
        zabbix.deleteManagedHost(id, input.revision());
        return Map.of("id", id);
    }
    @ExceptionHandler(ResponseStatusException.class) @ResponseBody
    public ResponseEntity<?> invalid(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of("message", Objects.requireNonNullElse(ex.getReason(), "Operação recusada.")));
    }
    @ExceptionHandler(RuntimeException.class) @ResponseBody
    public ResponseEntity<?> unavailable(RuntimeException ex) {
        // Never expose credentials, communities or remote API payloads in errors.
        return ResponseEntity.status(502).body(Map.of("message", "Não foi possível confirmar a operação no Zabbix. Verifique a integração e as permissões. Atualize a página antes de tentar novamente."));
    }
}
