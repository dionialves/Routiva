package br.com.scriptping.mikrotik;

import br.com.scriptping.zabbix.Host;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MikrotikService {
    private final String username, password;
    private final int port;
    private final boolean tls;
    private final Semaphore connections = new Semaphore(8);

    public MikrotikService(@Value("${MIKROTIK_USERNAME:}") String username,
            @Value("${MIKROTIK_PASSWORD:}") String password,
            @Value("${MIKROTIK_API_PORT:8729}") int port,
            @Value("${MIKROTIK_API_TLS:true}") boolean tls) {
        this.username = username; this.password = password; this.port = port; this.tls = tls;
    }

    @org.springframework.beans.factory.annotation.Autowired
    private WirelessQuality wirelessQuality;

    public record Snapshot(long measuredAt, List<Map<String, String>> rows, String warning) { }

    public Snapshot query(Host host, String section, String selectedInterface) {
        if (username.isBlank() || password.isBlank()) throw new ResponseStatusException(
            HttpStatus.SERVICE_UNAVAILABLE, "Configure MIKROTIK_USERNAME e MIKROTIK_PASSWORD no .env.");
        if (host.ip() == null || !host.ip().matches("[0-9a-fA-F:.]+"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Host sem endereço IP válido.");
        if (!connections.tryAcquire()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Consultas ocupadas. Tente novamente.");
        try (var api = new RouterOsConnection(host.ip(), port, tls, username, password)) {
            List<Map<String, String>> rows;
            String warning = "";
            switch (section) {
                case "overview" -> {
                    rows = api.command("/system/resource/print");
                    var identity = api.command("/system/identity/print");
                    if (!rows.isEmpty() && !identity.isEmpty()) rows.getFirst().put("identity", identity.getFirst().get("name"));
                }
                case "interfaces" -> rows = api.command("/interface/print", "=.proplist=.id,name,type,running,disabled,mtu,actual-mtu,mac-address,link-downs,rx-byte,tx-byte,rx-error,tx-error,rx-drop,tx-drop");
                case "traffic" -> {
                    if (selectedInterface == null || selectedInterface.length() > 128) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Selecione uma interface.");
                    var interfaces = api.command("/interface/print", "=.proplist=.id,name");
                    var selected = interfaces.stream().filter(i -> selectedInterface.equals(i.get(".id"))).findFirst()
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Interface não encontrada."));
                    rows = api.command("/interface/monitor-traffic", "=interface=" + selected.get(".id"), "=once=");
                }
                case "wireless" -> {
                    rows = new ArrayList<>();
                    int supported = 0;
                    for (String path : List.of("/interface/wireless/registration-table/print", "/interface/wifi/registration-table/print", "/interface/wifiwave2/registration-table/print")) {
                        try {
                            var clients = api.command(path);
                            for (var client : clients) client.put("source", path.contains("/wireless/") ? "Wireless" : "WiFi");
                            rows.addAll(clients);
                            supported++;
                        } catch (RouterOsConnection.CommandRejectedException ex) { /* Optional RouterOS package. */ }
                    }
                    var unique = new LinkedHashMap<String, Map<String, String>>();
                    for (var client : rows) unique.putIfAbsent(client.get("interface") + ":" + client.get("mac-address"), client);
                    rows = new ArrayList<>(unique.values());
                    if (supported == 0) warning = "Wireless indisponível: pacote ausente, recurso não suportado ou acesso negado.";
                }
                default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            }
            long measuredAt = System.currentTimeMillis();
            if (section.equals("wireless") && warning.isEmpty() && wirelessQuality != null)
                rows = wirelessQuality.update(host.id(), rows, measuredAt);
            return new Snapshot(measuredAt, rows, warning);
        } catch (RouterOsConnection.CommandRejectedException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "MikroTik recusou a consulta. Verifique credenciais, permissões e recursos disponíveis.");
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Não foi possível consultar o MikroTik. Verifique endereço, porta, API e certificado TLS.");
        } finally { connections.release(); }
    }
}
