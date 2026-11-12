package br.com.scriptping.ixc;

import br.com.scriptping.mikrotik.MikrotikService;
import br.com.scriptping.zabbix.ZabbixService;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class IxcController {
    private final IxcClient ixc;
    private final ZabbixService inventory;
    private final MikrotikService mikrotik;

    public IxcController(IxcClient ixc, ZabbixService inventory, MikrotikService mikrotik) {
        this.ixc = ixc; this.inventory = inventory; this.mikrotik = mikrotik;
    }

    @GetMapping("/api/hosts/{id}/ixc/login")
    public ResponseEntity<List<IxcClient.Login>> login(@PathVariable String id, @RequestParam String mac) {
        String normalized = IxcClient.normalizeMac(mac);
        var host = inventory.hosts().stream().filter(h -> h.id().equals(id)).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Host não encontrado."));
        boolean connected = mikrotik.query(host, "wireless", null).rows().stream().anyMatch(row -> {
            if ("disconnected".equals(row.get("connection-status"))) return false;
            try { return normalized.equals(IxcClient.normalizeMac(row.get("mac-address"))); }
            catch (ResponseStatusException ex) { return false; }
        });
        if (!connected) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "MAC não encontrado entre os clientes wireless deste host.");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ixc.findByMac(normalized));
    }

    // Router password is held in browser memory for the access click; never render it into HTML.
    public static final class ClientIdentity {
        public final String pppoe, ip, connectionPort, equipmentPassword;
        public final IxcClient.Plan plan;
        ClientIdentity(IxcClient.Login login, IxcClient.Plan plan) {
            pppoe = login.pppoe; ip = login.ip; connectionPort = login.connectionPort;
            equipmentPassword = login.equipmentPassword; this.plan = plan;
        }
    }
    @GetMapping("/api/hosts/{id}/ixc/client")
    public ResponseEntity<List<ClientIdentity>> client(@PathVariable String id, @RequestParam String mac) {
        var logins = login(id, mac).getBody();
        var result = new ArrayList<ClientIdentity>();
        for (var login : logins) {
            IxcClient.Plan plan;
            try { plan = ixc.findPlan(login.planId); }
            catch (ResponseStatusException unavailable) { plan = new IxcClient.Plan("", ""); }
            result.add(new ClientIdentity(login, plan));
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }

    @GetMapping("/api/hosts/{id}/ixc/login-names")
    public ResponseEntity<Map<String, List<String>>> loginNames(@PathVariable String id) {
        var host = inventory.hosts().stream().filter(h -> h.id().equals(id)).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Host não encontrado."));
        var names = new LinkedHashMap<String, List<String>>();
        for (var row : mikrotik.query(host, "wireless", null).rows()) {
            if ("disconnected".equals(row.get("connection-status"))) continue;
            try { names.putIfAbsent(IxcClient.normalizeMac(row.get("mac-address")), new ArrayList<>()); }
            catch (ResponseStatusException ex) { /* Ignore missing or malformed MACs. */ }
        }
        if (!names.isEmpty()) {
            for (var login : ixc.findByMacs(names.keySet())) {
                var matches = names.get(login.mac);
                if (matches != null && !login.pppoe.isBlank() && !matches.contains(login.pppoe)) matches.add(login.pppoe);
            }
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(names);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> error(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).cacheControl(CacheControl.noStore())
            .body(Map.of("message", Objects.requireNonNullElse(ex.getReason(), "Consulta indisponível.")));
    }
}
