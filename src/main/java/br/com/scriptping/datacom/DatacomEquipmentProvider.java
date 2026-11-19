package br.com.scriptping.datacom;

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
public class DatacomEquipmentProvider implements EquipmentProvider {
    private final HostConnections settings;
    private final UbiquitiSsh ssh;
    private final Semaphore connections = new Semaphore(8);
    private record Counter(long at, String rx, String tx, Connection credentials) {}
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();
    private record MappedSession(UbiquitiSsh.Link link, DatacomCli cli) implements AutoCloseable {
        @Override public void close() {
            try { cli.close(); } finally { link.close(); }
        }
    }
    private final br.com.scriptping.equipment.MappedEquipmentSessions<MappedSession> mappedSessions;
    public DatacomEquipmentProvider(HostConnections settings, UbiquitiSsh ssh) {
        this.settings = settings; this.ssh = ssh;
        mappedSessions = new br.com.scriptping.equipment.MappedEquipmentSessions<>((host, credentials) -> {
            var link = ssh.connect(host.ip(), credentials);
            try { return new MappedSession(link, link.datacomCli()); }
            catch (Exception ex) { link.close(); throw ex; }
        }, ex -> ex instanceof java.io.IOException && !(ex instanceof DatacomCli.CommandRejectedException)
            || ex instanceof com.jcraft.jsch.JSchException);
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 10000)
    public void expireMappedSessions() { mappedSessions.expireIdle(); }
    @jakarta.annotation.PreDestroy
    public void closeMappedSessions() { mappedSessions.close(); }
    public String manufacturer() { return "Datacom"; }
    public Set<String> capabilities() { return Set.of("overview", "interfaces", "traffic", "discover", "connection", "connection-status"); }
    public Snapshot read(Host host, String section, String selectedInterface) {
        return query(host, section, selectedInterface, settings.require(host));
    }
    public void testConnection(Host host, Connection connection) { query(host, "overview", null, connection); }
    public Snapshot query(Host host, String section, String selectedInterface, Connection credentials) {
        if (!capabilities().contains(section)) throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "Recurso não suportado pelo Datacom.");
        String selected;
        try { selected = selectedInterface == null ? null : DatacomData.cliName(selectedInterface); }
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
            try (var link = ssh.connect(host.ip(), credentials); var cli = link.datacomCli()) {
                return collect(host, section, selected, credentials, cli);
            }
        } catch (ResponseStatusException ex) { throw ex;
        } catch (DatacomCli.CommandRejectedException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "SSH conectado, mas o Datacom recusou o comando de consulta. Verifique as permissões do usuário e os comandos disponíveis nesta versão DmOS.");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Consulta SSH interrompida.");
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Não foi possível consultar o Datacom. Verifique SSH, credenciais, permissões e compatibilidade da saída DmOS.");
        } finally { if (acquired) connections.release(); }
    }
    private Snapshot collect(Host host, String section, String selected, Connection credentials, DatacomCli cli) throws Exception {
        List<Map<String, String>> rows;
        if (section.equals("overview")) {
            String platform = cli.command("show platform");
            var warnings = new ArrayList<String>();
            var row = DatacomData.overview(platform, optional(cli, "show firmware", warnings),
                optional(cli, "show system cpu", warnings), optional(cli, "show system memory", warnings),
                optional(cli, "show system uptime", warnings));
            row.put("identity", host.name());
            if (!row.keySet().containsAll(Set.of("version", "uptime", "cpu-load", "total-memory", "free-memory")))
                warnings.add("Algumas informações do equipamento não foram reconhecidas nesta versão do DmOS.");
            return new Snapshot(System.currentTimeMillis(), List.of(row), String.join(" ", warnings));
        }
        String interfaces = cli.command("show interface link");
        try { rows = DatacomData.links(interfaces); }
        catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "SSH conectado, mas o formato de show interface link não foi reconhecido. Confira a saída desse comando na versão DmOS do equipamento.");
        }
        if (selected != null) {
            String name = selected.replaceFirst(" (?=[0-9])", "-");
            rows = rows.stream().filter(row -> name.equals(row.get("name"))).toList();
            if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Interface não encontrada.");
        }
        if (section.equals("connection-status")) {
            var row = rows.getFirst();
            if (!"ether".equals(row.get("type")) || !row.containsKey("running") || !row.containsKey("disabled"))
                throw new IllegalArgumentException("Missing operational status");
            return new Snapshot(System.currentTimeMillis(), rows, "");
        }
        // Fixed-cost discovery: identify the hardware once, never query every port.
        if (section.equals("discover")) {
            rows = rows.stream().filter(row -> "ether".equals(row.get("type"))).toList();
            try { DatacomData.applyHardware(rows, cli.command("show platform")); }
            catch (java.io.IOException ex) { /* Optional hardware metadata; preserve discovered ports. */ }
            return new Snapshot(System.currentTimeMillis(), rows, "");
        }
        for (var row : rows) {
            String name = DatacomData.cliName(row.get("name"));
            DatacomData.details(row, cli.command("show interface " + name));
            DatacomData.details(row, cli.command("show interface " + name + " statistics"));
        }
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
                String rx = DatacomData.rate(before.rx(), row.get("rx-byte"), at - before.at());
                String tx = DatacomData.rate(before.tx(), row.get("tx-byte"), at - before.at());
                if (rx != null) row.put("rx-bits-per-second", rx);
                if (tx != null) row.put("tx-bits-per-second", tx);
            }
        }
        boolean partial = !section.equals("discover") && rows.stream().anyMatch(row -> !row.keySet().containsAll(Set.of("running", "disabled", "rx-byte", "tx-byte")));
        return new Snapshot(at, rows, partial ? "Leitura parcial: estado ou contadores indisponíveis em algumas interfaces." : "");
    }
    private String optional(DatacomCli cli, String command, List<String> warnings) throws Exception {
        try { return cli.command(command); }
        catch (DatacomCli.CommandRejectedException ex) {
            warnings.add("Leitura parcial: " + command + " indisponível.");
            return "";
        }
    }
}
