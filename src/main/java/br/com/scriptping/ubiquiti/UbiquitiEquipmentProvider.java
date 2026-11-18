package br.com.scriptping.ubiquiti;

import br.com.scriptping.hosts.HostConnections;
import br.com.scriptping.equipment.EquipmentProvider;
import br.com.scriptping.mikrotik.WirelessQuality;
import br.com.scriptping.zabbix.Host;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class UbiquitiEquipmentProvider implements EquipmentProvider {
    private final HostConnections settings;
    private final UbiquitiSsh ssh;
    private final ObjectMapper json;
    private final WirelessQuality quality;
    private final Semaphore connections = new Semaphore(8);
    private record Counter(long at, Map<String, String> row) {}
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();
    public UbiquitiEquipmentProvider(HostConnections settings, UbiquitiSsh ssh, ObjectMapper json, WirelessQuality quality) {
        this.settings = settings; this.ssh = ssh; this.json = json; this.quality = quality;
    }
    public String manufacturer() { return "Ubiquiti"; }
    public Set<String> capabilities() { return Set.of("overview", "interfaces", "wireless", "traffic"); }
    public void testConnection(Host host, Connection connection) { query(host, "overview", null, connection); }
    public Snapshot read(Host host, String section, String selectedInterface) { return query(host, section, selectedInterface, settings.require(host)); }
    private Snapshot query(Host host, String section, String selectedInterface, Connection connection) {
        if (host.ip() == null || !host.ip().matches("[0-9a-fA-F:.]+"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Host sem endereço IP válido.");
        if (!capabilities().contains(section)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (section.equals("traffic") && (selectedInterface == null || !selectedInterface.matches("[a-zA-Z0-9_.:-]{1,128}")))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Selecione uma interface válida.");
        if (!connections.tryAcquire()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Consultas ocupadas. Tente novamente.");
        try (var link = ssh.connect(host.ip(), connection)) {
            List<Map<String, String>> rows;
            if (section.equals("overview")) {
                var status = UbiquitiData.status(link.command("mca-status"));
                if (!status.containsKey("firmwareVersion") || !status.containsKey("platform"))
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "O equipamento não forneceu informações airOS válidas.");
                rows = List.of(UbiquitiData.overview(status));
            } else if (section.equals("wireless")) {
                rows = UbiquitiData.wireless(link.command("wstalist"), json);
            } else {
                rows = UbiquitiData.interfaces(link.command("cat /proc/net/dev"), link.command("ifconfig -a"));
                if (section.equals("traffic")) {
                    var selected = rows.stream().filter(row -> selectedInterface.equals(row.get(".id"))).findFirst()
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Interface não encontrada."));
                    long now = System.currentTimeMillis();
                    counters.entrySet().removeIf(entry -> now - entry.getValue().at() > 120000);
                    String key = host.id() + ":" + host.ip() + ":" + selectedInterface;
                    Counter previous = counters.put(key, new Counter(now, new LinkedHashMap<>(selected)));
                    if (previous != null && now > previous.at() && now - previous.at() <= 15000) {
                        for (String direction : List.of("rx", "tx")) {
                            var delta = new BigInteger(selected.get(direction + "-byte")).subtract(new BigInteger(previous.row().get(direction + "-byte")));
                            if (delta.signum() >= 0) selected.put(direction + "-bits-per-second", Long.toString(Math.round(delta.doubleValue() * 8000 / (now - previous.at()))));
                        }
                    }
                    rows = List.of(selected);
                }
            }
            long at = System.currentTimeMillis();
            if (section.equals("wireless")) {
                rows = quality.update("Ubiquiti:" + host.id(), rows, at);
                for (var row : rows) {
                    for (String key : List.of("quality-primary-reason", "quality-summary", "quality-reason", "critical-alert"))
                        if (row.containsKey(key)) row.put(key, row.get(key).replace("TX CCQ", "CCQ"));
                }
            }
            return new Snapshot(at, rows, "");
        } catch (ResponseStatusException ex) { throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Consulta SSH interrompida.");
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "Não foi possível consultar a Ubiquiti. Verifique IP, porta SSH, credenciais e chave do equipamento.");
        } finally { connections.release(); }
    }
}
