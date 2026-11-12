package br.com.scriptping.mikrotik;

import br.com.scriptping.zabbix.*;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Controller
public class MikrotikController {
    private final ZabbixService inventory;
    private final MikrotikService mikrotik;

    public MikrotikController(ZabbixService inventory, MikrotikService mikrotik) {
        this.inventory = inventory; this.mikrotik = mikrotik;
    }

    private Host host(String id) {
        return inventory.hosts().stream().filter(h -> h.id().equals(id)).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Host não encontrado no inventário monitorado."));
    }

    @GetMapping("/hosts/{id}")
    public String page(@PathVariable String id, Model model) {
        model.addAttribute("host", host(id));
        return "host";
    }

    @GetMapping("/api/hosts/{id}/mikrotik/{section:overview|interfaces|traffic|wireless}")
    @ResponseBody
    public ResponseEntity<MikrotikService.Snapshot> live(@PathVariable String id, @PathVariable String section,
            @RequestParam(required = false) String selectedInterface) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(mikrotik.query(host(id), section, selectedInterface));
    }

    @ExceptionHandler(ResponseStatusException.class)
    @ResponseBody
    public ResponseEntity<java.util.Map<String, String>> error(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).cacheControl(CacheControl.noStore())
            .body(java.util.Map.of("message", ex.getReason() == null ? "Consulta indisponível." : ex.getReason()));
    }

}
