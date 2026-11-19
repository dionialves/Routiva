package br.com.scriptping.cisco;

import br.com.scriptping.equipment.EquipmentProvider;
import br.com.scriptping.hosts.HostConnections;
import br.com.scriptping.ubiquiti.UbiquitiSsh;
import br.com.scriptping.zabbix.Host;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class CiscoEquipmentProvider implements EquipmentProvider {
    private final HostConnections settings;
    private final UbiquitiSsh ssh;
    private final Semaphore connections = new Semaphore(8);
    private record Counter(long at, String rx, String tx, Connection credentials) {}
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();
    private record MappedSession(UbiquitiSsh.Link link, CiscoCli cli) implements AutoCloseable {
        @Override public void close() {
            try { cli.close(); } finally { link.close(); }
        }
    }
    private final br.com.scriptping.equipment.MappedEquipmentSessions<MappedSession> mappedSessions;
    public CiscoEquipmentProvider(HostConnections settings, UbiquitiSsh ssh) {
        this.settings = settings; this.ssh = ssh;
        mappedSessions = new br.com.scriptping.equipment.MappedEquipmentSessions<>((host, credentials) -> {
            var link = ssh.connectCisco(host.ip(), credentials);
            try { return new MappedSession(link, link.ciscoCli()); }
            catch (Exception ex) { link.close(); throw ex; }
        }, ex -> ex instanceof java.io.IOException && !(ex instanceof CiscoCli.CommandRejectedException)
            || ex instanceof com.jcraft.jsch.JSchException);
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 10000)
    public void expireMappedSessions() { mappedSessions.expireIdle(); }
    @jakarta.annotation.PreDestroy
    public void closeMappedSessions() { mappedSessions.close(); }
    public String manufacturer() { return "Cisco"; }
    public Set<String> capabilities() { return Set.of("overview", "interfaces", "traffic", "discover", "connection", "connection-status"); }
    public Snapshot read(Host host, String section, String selectedInterface) {
        return query(host, section, selectedInterface, settings.require(host));
    }
    public void testConnection(Host host, Connection connection) { query(host, "overview", null, connection); }
    public Snapshot query(Host host, String section, String selectedInterface, Connection credentials) {
        if (!capabilities().contains(section)) throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "Recurso não suportado pelo Cisco.");
        String selected;
        try { selected = selectedInterface == null ? null : CiscoData.cliName(selectedInterface); }
        catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Selecione uma interface válida."); }
        if ((section.equals("traffic") || section.equals("connection") || section.equals("connection-status")) && selected == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Selecione uma interface.");
        boolean mapped = section.equals("connection") || section.equals("connection-status");
        boolean acquired = false;
        try {
            if (mapped) return mappedSessions.read(host, credentials,
                session -> collect(host, section, selected, credentials, session.cli()));
            if (!connections.tryAcquire()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Consultas ocupadas. Tente novamente.");
            acquired = true;
            try (var link = ssh.connectCisco(host.ip(), credentials); var cli = link.ciscoCli()) {
                return collect(host, section, selected, credentials, cli);
            }
        } catch (ResponseStatusException ex) { throw ex;
        } catch (CiscoCli.CommandRejectedException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "SSH conectado, mas o Cisco recusou o comando de consulta. Verifique as permissões do usuário e os comandos disponíveis nesta versão IOS XE.");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Consulta SSH interrompida.");
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Não foi possível consultar o Cisco. Verifique SSH, credenciais, permissões e compatibilidade da saída IOS XE.");
        } finally { if (acquired) connections.release(); }
    }
    private Snapshot collect(Host host, String section, String selected, Connection credentials, CiscoCli cli) throws Exception {
        if (section.equals("overview")) {
            var row = CiscoData.overview(cli.command("show version"));
            row.put("identity", host.name());
            return new Snapshot(System.currentTimeMillis(), List.of(row), "");
        }
        boolean statusOnly = section.equals("connection-status");
        List<Map<String, String>> rows = statusOnly
            ? CiscoData.status(cli.command("show interfaces description"))
            : CiscoData.interfaces(cli.command(selected == null ? "show interfaces" : "show interfaces " + selected));
        if (selected != null) {
            rows = rows.stream().filter(row -> selected.equals(row.get("name"))).toList();
            if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Interface não encontrada.");
        }
        if (section.equals("discover")) rows = rows.stream().filter(row -> "ether".equals(row.get("type"))).toList();
        if (statusOnly) return new Snapshot(System.currentTimeMillis(), rows, "");
        long at = System.currentTimeMillis();
        if (section.equals("connection")) {
            var row = rows.getFirst();
            if (!"ether".equals(row.get("type")) || !row.containsKey("running") || !row.containsKey("disabled"))
                throw new IllegalArgumentException("Missing operational status");
        }
        if (section.equals("traffic")) {
            var row = rows.getFirst();
            counters.entrySet().removeIf(e -> at - e.getValue().at() > 120000);
            String key = host.id() + ":" + host.ip() + ":" + selected;
            var before = counters.put(key, new Counter(at, row.get("rx-byte"), row.get("tx-byte"), credentials));
            if (before != null && before.credentials().equals(credentials)) {
                String rx = br.com.scriptping.datacom.DatacomData.rate(before.rx(), row.get("rx-byte"), at - before.at());
                String tx = br.com.scriptping.datacom.DatacomData.rate(before.tx(), row.get("tx-byte"), at - before.at());
                if (rx != null) row.put("rx-bits-per-second", rx);
                if (tx != null) row.put("tx-bits-per-second", tx);
            }
        }
        boolean partial = !section.equals("discover") && rows.stream().anyMatch(row -> !row.keySet().containsAll(Set.of("running", "disabled", "rx-byte", "tx-byte")));
        return new Snapshot(at, rows, partial ? "Leitura parcial: estado ou contadores indisponíveis em algumas interfaces." : "");
    }
}
