package br.com.scriptping;

import java.net.URI;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class MonitorService {
    private final ZabbixClient zabbix;
    private final CollectorClient collector;
    private final ZabbixSettings settings;
    private volatile String status = "Aguardando sincronização manual";

    public MonitorService(ZabbixClient zabbix, CollectorClient collector, ZabbixSettings settings) {
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
            .map(h -> new Host(h.id(), h.name(), h.ip(), h.groups().stream().filter(g -> groups.contains(g.id())).toList(), h.acknowledgedIcmpProblem()))
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
    public List<Host> hosts() { return settings.current().inventory(); }
    public String status() { return status; }
    public Long lastSync() { return settings.current().lastSync(); }
}
