package br.com.scriptping.mikrotik;

import br.com.scriptping.zabbix.Host;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MikrotikService {
    private final br.com.scriptping.hosts.HostConnections settings;
    private final Semaphore connections = new Semaphore(8);
    private final MappedRouterSessions mappedSessions = new MappedRouterSessions();

    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 10000)
    public void expireMappedSessions() { mappedSessions.expireIdle(); }

    @jakarta.annotation.PreDestroy
    public void closeMappedSessions() { mappedSessions.close(); }
    public MikrotikService(br.com.scriptping.hosts.HostConnections settings) { this.settings = settings; }

    public void testConnection(Host host, br.com.scriptping.equipment.EquipmentProvider.Connection connection) {
        var snapshot = query(host, "overview", null, connection);
        String version = snapshot.rows().isEmpty() ? "" : snapshot.rows().getFirst().getOrDefault("version", "");
        RouterOsProfile.from(version);
    }

    @org.springframework.beans.factory.annotation.Autowired
    private WirelessQuality wirelessQuality;

    public record Snapshot(long measuredAt, List<Map<String, String>> rows, String warning) { }

    public Snapshot query(Host host, String section, String selectedInterface) {
        return query(host, section, selectedInterface, settings.require(host));
    }

    public Snapshot query(Host host, String section, String selectedInterface,
            br.com.scriptping.equipment.EquipmentProvider.Connection connection) {
        return query(host, section, selectedInterface, connection, connection.tls());
    }

    Snapshot query(Host host, String section, String selectedInterface,
            br.com.scriptping.equipment.EquipmentProvider.Connection connection, boolean tls) {
        if (host.ip() == null || !host.ip().matches("[0-9a-fA-F:.]+"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Host sem endereço IP válido.");
        if (section.equals("connection") || section.equals("connection-status")) {
            try {
                var credentials = new br.com.scriptping.equipment.EquipmentProvider.Connection(
                    connection.login(), connection.password(), connection.port(), tls);
                return mappedSessions.read(host, credentials, api -> mappedInterface(api, selectedInterface, section.equals("connection")));
            } catch (RouterOsConnection.CommandRejectedException ex) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "MikroTik recusou a consulta. Verifique credenciais e permissões.");
            } catch (IOException ex) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Não foi possível consultar o MikroTik. A sessão será refeita na próxima coleta.");
            }
        }
        if (!connections.tryAcquire()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Consultas ocupadas. Tente novamente.");
        try (var api = new RouterOsConnection(host.ip(), connection.port(), tls, connection.login(), connection.password())) {
            List<Map<String, String>> rows;
            String warning = "";
            switch (section) {
                case "overview" -> {
                    rows = api.command("/system/resource/print");
                    var identity = api.command("/system/identity/print");
                    if (!rows.isEmpty() && !identity.isEmpty()) rows.getFirst().put("identity", identity.getFirst().get("name"));
                    if (!rows.isEmpty()) {
                        var radios = new ArrayList<Map<String, String>>();
                        var profile = RouterOsProfile.from(rows.getFirst().get("version"));
                        for (String menu : profile.radioMenus()) {
                            String path = menu + "/print";
                            try {
                                radios.addAll(api.command(path,
                                    "=.proplist=name,frequency,channel-width,ssid,configuration.ssid,channel.frequency,channel.width"));
                            } catch (RouterOsConnection.CommandRejectedException ex) {
                                if (!ex.missingCommand()) warning = "Não foi possível consultar todos os recursos de rádio. Verifique permissões.";
                            }
                        }
                        for (String field : List.of("frequency", "ssid")) {
                            var values = new LinkedHashSet<String>();
                            for (var radio : radios) {
                                String value = radio.getOrDefault(field, radio.get(field.equals("ssid") ? "configuration.ssid" : "channel.frequency"));
                                if (value != null && !value.isBlank()) values.add(value);
                            }
                            if (!values.isEmpty()) rows.getFirst().put(field, String.join(" / ", values));
                        }
                        var channels = new LinkedHashSet<String>();
                        for (var radio : radios) {
                            String frequency = radio.getOrDefault("frequency", radio.get("channel.frequency"));
                            String width = radio.getOrDefault("channel-width", radio.get("channel.width"));
                            if (frequency != null && width != null) channels.add(frequency + " MHz / " + width);
                        }
                        if (!channels.isEmpty()) rows.getFirst().put("channel", String.join(" · ", channels));
                    }
                }
                case "discover" -> {
                    rows = api.command("/interface/ethernet/print",
                        "=.proplist=.id,name,default-name,comment,type,running,disabled,speed,sfp-supported,advertise");
                    for (var row : rows) {
                        var status = api.command("/interface/ethernet/monitor", "=numbers=" + row.get(".id"), "=once=");
                        if (!status.isEmpty()) row.putAll(status.getFirst());
                        // Some ports omit advertising from monitor (for example without a link).
                        // Preserve their own configured modes, never the link partner or current speed.
                        if (row.getOrDefault("advertising", "").isBlank()
                                && !row.getOrDefault("advertise", "").isBlank())
                            row.put("advertising", row.get("advertise"));
                    }
                }
                case "interfaces" -> {
                    rows = api.command("/interface/print", "=.proplist=.id,name,comment,type,running,disabled,mtu,actual-mtu,mac-address,uptime,link-downs");
                    var stats = api.command("/interface/print", "=stats-detail=",
                        "=.proplist=.id,name,rx-byte,tx-byte,rx-error,tx-error,rx-drop,tx-drop");
                    for (var row : rows) {
                        stats.stream().filter(stat -> Objects.equals(row.get(".id"), stat.get(".id")))
                            .findFirst().ifPresent(row::putAll);
                    }
                }
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
                    var resources = api.command("/system/resource/print", "=.proplist=version");
                    var profile = RouterOsProfile.from(resources.isEmpty() ? "" : resources.getFirst().get("version"));
                    boolean denied = false;
                    for (String menu : profile.radioMenus()) {
                        String path = menu + "/registration-table/print";
                        try {
                            var clients = api.command(path);
                            for (var client : clients) client.put("source", path.contains("/wireless/") ? "Wireless" : "WiFi");
                            rows.addAll(clients);
                            supported++;
                        } catch (RouterOsConnection.CommandRejectedException ex) {
                            if (!ex.missingCommand()) denied = true;
                        }
                    }
                    var unique = new LinkedHashMap<String, Map<String, String>>();
                    for (var client : rows) unique.putIfAbsent(client.get("interface") + ":" + client.get("mac-address"), client);
                    rows = new ArrayList<>(unique.values());
                    if (denied) warning = "Consulta wireless incompleta: acesso negado ou comando recusado. Verifique permissões.";
                    else if (supported == 0) warning = "Wireless indisponível: pacote ausente ou recurso não suportado.";
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
    private Snapshot mappedInterface(RouterOsConnection api, String name, boolean traffic) throws IOException {
        if (name == null || name.isBlank() || name.length() > 128)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Selecione uma interface.");
        var interfaces = api.command("/interface/print", "=.proplist=.id,name,type,running,disabled");
        var selected = interfaces.stream().filter(i -> name.equals(i.get("name")) && "ether".equals(i.get("type"))).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Interface Ethernet não encontrada."));
        var row = new HashMap<>(selected);
        if (!traffic) return new Snapshot(System.currentTimeMillis(), List.of(row), "");
        var stats = api.command("/interface/print", "=stats-detail=", "?name=" + name, "=.proplist=rx-byte,tx-byte");
        if (!stats.isEmpty()) row.putAll(stats.getFirst());
        var status = api.command("/interface/ethernet/monitor", "=numbers=" + selected.get(".id"), "=once=");
        if (!status.isEmpty()) row.putAll(status.getFirst());
        return new Snapshot(System.currentTimeMillis(), List.of(row), "");
    }

}
