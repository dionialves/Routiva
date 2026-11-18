package br.com.scriptping.datacom;

import br.com.scriptping.hosts.HostRepository;
import java.util.*;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Controller
public class DatacomController {
    private final DatacomEquipmentProvider provider;
    private final HostRepository hosts;
    public DatacomController(DatacomEquipmentProvider provider, HostRepository hosts) { this.provider = provider; this.hosts = hosts; }
    private br.com.scriptping.zabbix.Host host(String id) {
        var host = hosts.target(id);
        var credentials = hosts.credentials(id);
        if (credentials == null || !"Datacom".equals(credentials.manufacturer()))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Host sem autenticação Datacom configurada.");
        return host;
    }
    @GetMapping("/api/equipment/datacom") @ResponseBody
    public ResponseEntity<Set<String>> equipment() {
        var ids = new HashSet<String>();
        hosts.credentials().forEach((id, credentials) -> { if ("Datacom".equals(credentials.manufacturer())) ids.add(id); });
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ids);
    }
    @GetMapping("/equipamentos/{id}")
    public String page(@PathVariable String id, Model model) { model.addAttribute("host", host(id)); return "datacom"; }
    @GetMapping("/api/hosts/{id}/datacom/{section:overview|interfaces|traffic}") @ResponseBody
    public ResponseEntity<?> read(@PathVariable String id, @PathVariable String section, @RequestParam(required = false) String selectedInterface) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(provider.read(host(id), section, selectedInterface));
    }
    @ExceptionHandler(ResponseStatusException.class) @ResponseBody
    public ResponseEntity<?> error(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).cacheControl(CacheControl.noStore())
            .body(Map.of("message", Objects.requireNonNullElse(ex.getReason(), "Consulta indisponível.")));
    }
}
