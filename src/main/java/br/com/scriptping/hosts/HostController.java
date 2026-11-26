package br.com.scriptping.hosts;

import java.util.*;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Controller
public class HostController {
    private final HostCatalog catalog;
    private final HostAuthentication authentication;
    private final br.com.scriptping.zabbix.ZabbixService inventory;
    public HostController(HostCatalog catalog, HostAuthentication authentication, br.com.scriptping.zabbix.ZabbixService inventory) {
        this.catalog = catalog; this.authentication = authentication; this.inventory = inventory;
    }
    @GetMapping("/configuracao/hosts") public String page() { return "hosts"; }
    public record HostRow(@com.fasterxml.jackson.annotation.JsonUnwrapped ManagedHost configuration, String connectionStatus, boolean backupEnabled) {}
    @GetMapping("/api/host-configurations") @ResponseBody
    public List<HostRow> list() {
        var hosts = catalog.all();
        var statuses = authentication.connectionStatuses(hosts);
        var backupEnabled = catalog.backupEnabledIds();
        return hosts.stream().map(host -> new HostRow(host, statuses.getOrDefault(host.id(), "NOT_CONFIGURED"),
            backupEnabled.contains(host.id()))).toList();
    }
    @GetMapping("/api/host-configurations/options") @ResponseBody
    public Map<String, ?> options() {
        return catalog.options();
    }
    @PostMapping("/api/host-configurations") @ResponseBody
    public Map<String, String> create(@RequestBody ManagedHost.Input input) {
        String saved = catalog.save(null, input);
        inventory.refreshLocalHosts();
        return Map.of("id", saved);
    }
    @PutMapping("/api/host-configurations/{id}") @ResponseBody
    public Map<String, String> update(@PathVariable String id, @RequestBody ManagedHost.Input input) {
        String saved = catalog.save(id, input);
        inventory.refreshLocalHosts();
        return Map.of("id", saved);
    }
    @PostMapping("/api/host-configurations/synchronize") @ResponseBody
    public List<HostRow> synchronize() { inventory.sync(); return list(); }
    @GetMapping("/api/host-configurations/{id}/authentication") @ResponseBody
    public HostAuthentication.View authentication(@PathVariable String id) { return authentication.view(id); }
    @PutMapping("/api/host-configurations/{id}/authentication") @ResponseBody
    public HostAuthentication.View authentication(@PathVariable String id, @RequestBody HostAuthentication.Input input) { return authentication.save(id, input); }
    @PostMapping("/api/host-configurations/{id}/authentication/test") @ResponseBody
    public HostAuthentication.Result test(@PathVariable String id, @RequestBody HostAuthentication.Input input) { return authentication.test(id, input); }
    @GetMapping("/api/hosts/{id}/terminal-access") @ResponseBody
    public HostAuthentication.TerminalAccess terminalAccess(@PathVariable String id) {
        return authentication.terminalAccess(id);
    }
    @PutMapping("/api/host-configurations/{id}/authentication/terminal-ports") @ResponseBody
    public HostRepository.TerminalPorts terminalPorts(@PathVariable String id, @RequestBody HostRepository.TerminalPorts ports) {
        return authentication.saveTerminalPorts(id, ports);
    }
    @ModelAttribute
    public void noCache(jakarta.servlet.http.HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }
    public record DeleteInput(String revision, boolean confirmed) {}
    @DeleteMapping("/api/host-configurations/{id}") @ResponseBody
    public Map<String, String> delete(@PathVariable String id, @RequestBody DeleteInput input) {
        if (!input.confirmed()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Confirme a exclusão do host.");
        catalog.delete(id, input.revision());
        inventory.forgetHosts(Set.of(id));
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
