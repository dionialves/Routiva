package br.com.scriptping.zabbix;

import br.com.scriptping.ingestion.CollectorClient;

import java.net.URI;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class ZabbixService {
    private final ZabbixClient zabbix;
    private final CollectorClient collector;
    private final ZabbixSettings settings;
    private volatile String status = "Aguardando sincronização manual";

    public ZabbixService(ZabbixClient zabbix, CollectorClient collector, ZabbixSettings settings) {
        this.zabbix = zabbix;
        this.collector = collector;
        this.settings = settings;
        var s = settings.current();
        zabbix.configure(s.url(), s.username(), s.password());
        collector.synchronize(s.inventory());
    }

    public synchronized void connection(String url, String username, String password) {
        URI uri;
        try { uri = URI.create(url.strip()); }
        catch (Exception ex) { throw new IllegalArgumentException("Informe uma URL válida."); }
        if (!Set.of("http", "https").contains(Objects.toString(uri.getScheme(), "")) || uri.getHost() == null
            || uri.getUserInfo() != null || uri.getFragment() != null)
            throw new IllegalArgumentException("Informe a URL HTTP ou HTTPS da API Zabbix.");
        var s = settings.current();
        boolean changed = !s.url().equals(url.strip());
        String secret = password.isEmpty() && !changed ? s.password() : password;
        if (username.isBlank() || secret.isBlank()) throw new IllegalArgumentException("Informe usuário e senha.");
        settings.save(new ZabbixSettings.State(url.strip(), username.strip(), secret,
            changed ? Set.of() : s.groups(), changed ? Set.of() : s.hosts(),
            changed ? List.of() : s.inventory(), changed ? null : s.lastSync()));
        zabbix.configure(url.strip(), username.strip(), secret);
        collector.synchronize(hosts());
        status = "Conexão salva. Teste a conexão antes de sincronizar.";
    }

    public synchronized void selectGroups(Set<String> ids) {
        var valid = zabbix.groups().stream().map(Host.Group::id).collect(java.util.stream.Collectors.toSet());
        if (!valid.containsAll(ids)) throw new IllegalArgumentException("Atualize a lista de grupos e tente novamente.");
        var s = settings.current();
        var eligible = zabbix.discover(ids).stream().map(Host::id).collect(java.util.stream.Collectors.toSet());
        var selected = new HashSet<>(s.hosts());
        selected.retainAll(eligible);
        selection(Set.copyOf(ids), Set.copyOf(selected));
    }

    public synchronized void selectHosts(Set<String> ids) {
        var s = settings.current();
        var valid = zabbix.discover(s.groups()).stream().map(Host::id).collect(java.util.stream.Collectors.toSet());
        if (!valid.containsAll(ids)) throw new IllegalArgumentException("Atualize a lista de hosts e tente novamente.");
        selection(s.groups(), Set.copyOf(ids));
    }

    public synchronized void selectInventory(Set<String> groups, Set<String> hosts) {
        var validGroups = zabbix.groups().stream().map(Host.Group::id).collect(java.util.stream.Collectors.toSet());
        if (!validGroups.containsAll(groups)) throw new IllegalArgumentException("Atualize a lista de grupos e tente novamente.");
        var validHosts = zabbix.discover(groups).stream().map(Host::id).collect(java.util.stream.Collectors.toSet());
        if (!validHosts.containsAll(hosts)) throw new IllegalArgumentException("Atualize a lista de hosts e tente novamente.");
        selection(Set.copyOf(groups), Set.copyOf(hosts));
    }

    private void selection(Set<String> groups, Set<String> hosts) {
        var s = settings.current();
        var inventory = authorized(s.inventory(), groups, hosts);
        settings.save(new ZabbixSettings.State(s.url(), s.username(), s.password(), groups, hosts, inventory, s.lastSync()));
        collector.synchronize(inventory);
    }

    static List<Host> authorized(List<Host> inventory, Set<String> groups, Set<String> hosts) {
        return inventory.stream().filter(h -> hosts.contains(h.id()))
            .filter(h -> h.groups().stream().anyMatch(g -> groups.contains(g.id())))
            .map(h -> new Host(h.id(), h.name(), h.ip(), h.groups().stream().filter(g -> groups.contains(g.id())).toList(), h.acknowledgedIcmpProblem(), h.hostname()))
            .toList();
    }

    public synchronized void sync() {
        if (!zabbix.configured()) throw new IllegalArgumentException("Salve a conexão com o Zabbix primeiro.");
        status = "Sincronização em andamento";
        try {
            var s = settings.current();
            var inventory = authorized(zabbix.fetchSelected(s.groups(), s.hosts()), s.groups(), s.hosts());
            settings.save(new ZabbixSettings.State(s.url(), s.username(), s.password(), s.groups(), s.hosts(), inventory, System.currentTimeMillis()));
            collector.synchronize(inventory);
            status = "Sincronização concluída: " + inventory.size() + " hosts autorizados";
        } catch (Exception ex) {
            status = "Falha na sincronização; último inventário preservado";
            throw ex;
        }
    }
    /** Discovery is read-only: never alters authorization or the running collector. */
    public synchronized Map<String, Object> inventory() {
        var groups = zabbix.groups();
        var hosts = zabbix.discover(groups.stream().map(Host.Group::id).collect(java.util.stream.Collectors.toSet()));
        var s = settings.current();
        return Map.of("groups", groups, "hosts", hosts, "selectedGroups", s.groups(), "selectedHosts", s.hosts(),
            "imported", s.imported(), "queriedAt", System.currentTimeMillis(), "result", s.importResult());
    }

    public synchronized Map<String, Object> saveAndImport(Set<String> ids) {
        var s = settings.current();
        var groups = zabbix.groups();
        var groupIds = groups.stream().map(Host.Group::id).collect(java.util.stream.Collectors.toSet());
        var available = new LinkedHashMap<String, Host>();
        zabbix.discover(groupIds).forEach(h -> available.put(h.id(), h));
        var known = new HashSet<>(available.keySet());
        known.addAll(s.hosts());
        if (!known.containsAll(ids)) throw new IllegalArgumentException("Seleção inválida. Atualize o inventário.");
        var discovered = Map.copyOf(available);
        // Persist authorization first; an import failure must be distinguishable from a save failure.
        var active = s.inventory().stream().filter(h -> ids.contains(h.id())).toList();
        settings.save(new ZabbixSettings.State(s.url(), s.username(), s.password(), groupIds,
            Set.copyOf(ids), active, s.lastSync(), s.imported(), Map.of("state", "running")));
        try {
            collector.synchronize(active);
            available.clear();
            zabbix.fetchSelected(groupIds, ids).forEach(h -> available.put(h.id(), h));
            var imported = new LinkedHashMap<String, Host>();
            s.imported().forEach(h -> imported.put(h.id(), h));
            var previous = new HashMap<String, Host>();
            active.forEach(h -> previous.put(h.id(), h));
            var next = new ArrayList<Host>();
            var errors = new ArrayList<Map<String, String>>();
            var entries = new ArrayList<ImportEntry>();
            long timestamp = System.currentTimeMillis();
            int created = 0, updated = 0, unchanged = 0, ignored = 0;
            for (String id : new TreeSet<>(ids)) {
                Host host = available.get(id);
                if (host == null) {
                    ignored++;
                    entries.add(entry(id, imported.get(id), "missing", "Origem", "warning",
                        "Ausente da origem; dados anteriores preservados.", timestamp));
                    errors.add(Map.of("id", id, "reason", "Ausente da origem; dados anteriores preservados."));
                    if (previous.containsKey(id)) next.add(previous.get(id));
                    continue;
                }
                if (!br.com.scriptping.IpAddress.isValid(host.ip()) || host.ip().contains(":")) {
                    entries.add(entry(id, host, "failed", "Erro de validação", "error",
                        "O coletor exige um endereço IPv4 válido.", timestamp));
                    errors.add(Map.of("id", id, "reason", "O coletor exige um endereço IPv4 válido."));
                    continue;
                }
                var old = imported.put(id, host);
                if (old == null) {
                    created++;
                    entries.add(entry(id, host, "imported", "Sucesso", "success", "Host importado.", timestamp));
                } else if (old.equals(host)) {
                    unchanged++;
                    entries.add(entry(id, host, "unchanged", "Informação", "info", "Host já existente, sem alterações.", timestamp));
                } else {
                    updated++;
                    entries.add(entry(id, host, "updated", "Sucesso", "success", "Dados do host atualizados.", timestamp));
                }
                next.add(host);
            }
            var result = new LinkedHashMap<String, Object>();
            result.put("state", errors.isEmpty() ? "completed" : "partial");
            result.put("imported", created); result.put("updated", updated);
            result.put("unchanged", unchanged); result.put("ignored", ignored);
            result.put("failed", errors.size() - ignored); result.put("errors", errors);
            result.put("finishedAt", System.currentTimeMillis());
            result.put("entries", entries);
            result.put("selectionSaved", true);
            settings.save(new ZabbixSettings.State(s.url(), s.username(), s.password(), groupIds,
                Set.copyOf(ids), List.copyOf(next), System.currentTimeMillis(), List.copyOf(imported.values()), result));
            collector.synchronize(next);
            status = "Seleção salva e importação concluída.";
            return Map.of("message", status, "result", result, "imported", settings.current().imported(), "selectedHosts", ids);
        } catch (Exception ex) {
            String reason = ex instanceof IllegalStateException || ex instanceof IllegalArgumentException
                ? Objects.toString(ex.getMessage(), "Falha na importação.")
                : "Falha ao consultar a origem; verifique a conexão e tente novamente.";
            var previous = new HashMap<String, Host>();
            s.imported().forEach(h -> previous.put(h.id(), h));
            discovered.forEach(previous::putIfAbsent);
            long timestamp = System.currentTimeMillis();
            var failure = new LinkedHashMap<String, Object>();
            failure.put("state", "failed"); failure.put("selectionSaved", true);
            failure.put("imported", 0); failure.put("updated", 0); failure.put("unchanged", 0);
            failure.put("ignored", 0); failure.put("failed", ids.size()); failure.put("finishedAt", timestamp);
            failure.put("errors", ids.stream().sorted().map(id -> Map.of("id", id, "reason", reason)).toList());
            failure.put("entries", ids.stream().sorted().map(id -> entry(id, previous.get(id), "failed",
                "Importação", "error", reason, timestamp)).toList());
            settings.save(new ZabbixSettings.State(s.url(), s.username(), s.password(), groupIds,
                Set.copyOf(ids), active, s.lastSync(), s.imported(), failure));
            status = "Seleção salva, mas a importação falhou. Consulte o resultado e tente novamente.";
            return Map.of("message", status, "result", failure, "imported", settings.current().imported(), "selectedHosts", ids);
        }
    }

    public record ImportEntry(String id, String name, String ip, String hostname, String outcome,
                              String category, String severity, String message, long timestamp) {}

    private static ImportEntry entry(String id, Host host, String outcome, String category,
                                     String severity, String message, long timestamp) {
        return new ImportEntry(id, host == null ? "" : host.name(), host == null ? "" : host.ip(),
            host == null ? "" : host.hostname(), outcome, category, severity, message, timestamp);
    }

    public List<Host> hosts() { return settings.current().inventory(); }
    public String status() { return status; }
    public Long lastSync() { return settings.current().lastSync(); }
}
