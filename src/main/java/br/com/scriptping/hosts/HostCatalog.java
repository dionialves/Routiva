package br.com.scriptping.hosts;

import br.com.scriptping.zabbix.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class HostCatalog {
    private final HostRepository repository;
    private final ZabbixClient zabbix;
    private final ZabbixSettings settings;
    private final org.springframework.transaction.support.TransactionTemplate transactions;
    public HostCatalog(HostRepository repository, ZabbixClient zabbix, ZabbixSettings settings,
                       org.springframework.transaction.PlatformTransactionManager manager) {
        this.repository = repository; this.zabbix = zabbix; this.settings = settings;
        this.transactions = new org.springframework.transaction.support.TransactionTemplate(manager);
    }
    public Set<String> backupEnabledIds() { return repository.backupEnabledIds(); }

    public List<ManagedHost> all() { return repository.all(); }
    public String revision(String id) { return repository.require(id).revision(); }
    public Map<String, ?> options() { return repository.options(); }
    public synchronized void restoreLegacy() {
        if (!repository.all().isEmpty()) return;
        var state = settings.current();
        if (repository.source() != null) return;
        var legacy = new LinkedHashMap<String, Host>();
        state.imported().forEach(h -> legacy.put(h.id(), h));
        state.inventory().forEach(h -> legacy.put(h.id(), h));
        if (legacy.isEmpty()) return;
        transactions.executeWithoutResult(tx -> {
            for (var h : legacy.values()) repository.put(new ManagedHost(h.id(), h.hostname(), h.name(),
                h.groups().stream().map(g -> new ManagedHost.Choice(g.id(), g.name())).toList(), List.of(), "", h.ip(), "", true,
                "161", 2, "", true, "", false, 0, 0, "unknown", ""));
            repository.source(state.url());
            var groups = legacy.values().stream().flatMap(h -> h.groups().stream()).distinct().toList();
            repository.options(Map.of("groups", groups, "templates", List.of()));
        });
    }
    public synchronized void resetSource(String url) {
        transactions.executeWithoutResult(tx -> {
            repository.reset(); repository.source(url);
            repository.options(Map.of("groups", List.of(), "templates", List.of()));
        });
    }
    public synchronized void reconcile() {
        if (!zabbix.configured()) return;
        // Complete all remote reads before changing local state, including removals.
        var hosts = zabbix.managedHosts();
        var raw = zabbix.mirroredConfigurations();
        var groups = zabbix.groups();
        var templates = zabbix.hostTemplates();
        String source = settings.current().url();
        transactions.executeWithoutResult(tx -> {
            if (repository.source() != null && !repository.source().equals(source)) repository.reset();
            var ids = hosts.stream().map(ManagedHost::id).collect(java.util.stream.Collectors.toSet());
            for (var previous : repository.trackedIds()) if (!ids.contains(previous)) repository.delete(previous);
            hosts.forEach(repository::put);
            raw.forEach(row -> repository.raw(row.path("hostid").asText(), row));
            repository.source(source);
            repository.options(Map.of("groups", groups, "templates", templates));
        });
    }
    public synchronized String save(String id, ManagedHost.Input input) {
        String saved = zabbix.saveManagedHost(id, input);
        var groups = input.groupIds().stream().map(value -> new ManagedHost.Choice(value, value)).toList();
        var templates = (input.templateIds() == null ? List.<String>of() : input.templateIds()).stream()
            .map(value -> new ManagedHost.Choice(value, value)).toList();
        // Persist confirmed writes immediately, even when the subsequent remote read is unavailable.
        repository.put(new ManagedHost(saved, input.hostname(), input.visibleName() == null || input.visibleName().isBlank() ? input.hostname() : input.visibleName(),
            groups, templates, "", input.ip(), input.dns(), input.useIp(), input.port(), input.snmpVersion(), input.community(),
            input.active(), "", true, 0, 0, "unknown", Objects.requireNonNullElse(input.description(), "")));
        try { reconcile(); }
        catch (RuntimeException ex) {
            org.slf4j.LoggerFactory.getLogger(HostCatalog.class).warn("Host salvo no Zabbix; atualização local será reconciliada.");
        }
        return saved;
    }
    public synchronized void delete(String id, String revision) {
        zabbix.deleteManagedHost(id, revision);
        transactions.executeWithoutResult(tx -> repository.delete(id));
    }
}
