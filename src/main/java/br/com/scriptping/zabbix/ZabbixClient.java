package br.com.scriptping.zabbix;


import com.fasterxml.jackson.databind.JsonNode;
import br.com.scriptping.hosts.ManagedHost;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class ZabbixClient {

    private String url, username, password;
    private final RestClient client;
    private String session;
    private boolean versionDetected, legacyAuth, legacyGroups, legacyEvents;

    public ZabbixClient(
        @Value("${zabbix.url}") String url,
        @Value("${zabbix.username}") String username,
        @Value("${zabbix.password}") String password
    ) {
        this.url = url;
        this.username = username;
        this.password = password;
        var factory = new JdkClientHttpRequestFactory(
            HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build()
        );
        factory.setReadTimeout(Duration.ofSeconds(20));
        // Some PHP/proxy deployments reject chunked JSON bodies; buffering provides Content-Length.
        client = RestClient.builder()
            .requestFactory(
                new org.springframework.http.client.BufferingClientHttpRequestFactory(factory)
            )
            .build();
    }

    public synchronized void configure(String url, String username, String password) {
        session = null;
        versionDetected = false;
        this.url = url;
        this.username = username;
        this.password = password;
    }

    public synchronized void testConnection() {
        versionDetected = false;
        detectVersion();
        login();
        authenticatedRequest("hostgroup.get", Map.of("output", List.of("groupid"), "limit", 1));
    }

    public synchronized List<Host.Group> groups() {
        detectVersion();
        if (session == null) login();
        List<Host.Group> groups = new ArrayList<>();
        for (var g : authenticatedRequest("hostgroup.get", Map.of("output", List.of("groupid", "name"))))
            groups.add(new Host.Group(g.path("groupid").asText(), g.path("name").asText()));
        groups.sort(Comparator.comparing(Host.Group::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(groups);
    }

    public synchronized List<Host> discover(Set<String> groupIds) {
        if (groupIds.isEmpty()) return List.of();
        return fetchInventory(groupIds, null, false);
    }

    public synchronized List<Host> fetchSelected(Set<String> groupIds, Set<String> hostIds) {
        if (groupIds.isEmpty() || hostIds.isEmpty()) return List.of();
        return fetchInventory(groupIds, hostIds, true);
    }

    public record Incident(String name, int severity, String eventId, long openedAt, boolean acknowledged, boolean hasMessages, int messageCount) {
        public Incident(String name, int severity, String eventId, long openedAt, boolean acknowledged) {
            this(name, severity, eventId, openedAt, acknowledged, false, 0);
        }
        public Incident(String name, int severity, String eventId, long openedAt) {
            this(name, severity, eventId, openedAt, false);
        }
        public Incident(String name, int severity) { this(name, severity, "", 0); }
    }

    public record MapHost(String id, String name, List<Host.Group> groups, String monitoring) {}

    public synchronized List<MapHost> mapHosts(Set<String> groupIds, Set<String> hostIds) {
        if (groupIds.isEmpty() && hostIds.isEmpty()) return List.of();
        detectVersion();
        if (session == null) login();
        Map<String, MapHost> hosts = new LinkedHashMap<>();
        // Separate batch queries implement the union of group and explicit host bindings.
        for (var target : List.of(Map.entry("groupids", groupIds), Map.entry("hostids", hostIds))) {
            if (target.getValue().isEmpty()) continue;
            var rows = authenticatedRequest("host.get", Map.of(target.getKey(), target.getValue(),
                "output", List.of("hostid", "name", "status"),
                legacyGroups ? "selectGroups" : "selectHostGroups", List.of("groupid", "name")));
            for (var row : rows) {
                var groups = new ArrayList<Host.Group>();
                for (var group : row.path(legacyGroups ? "groups" : "hostgroups"))
                    groups.add(new Host.Group(group.path("groupid").asText(), group.path("name").asText()));
                // Interface availability does not describe monitoring status (e.g. ICMP-only hosts).
                // Map health is determined by active incidents after applying the map threshold.
                String monitoring = row.path("status").asInt() != 0 ? "disabled" : "monitored";
                var host = new MapHost(row.path("hostid").asText(), row.path("name").asText(), List.copyOf(groups), monitoring);
                hosts.put(host.id(), host);
            }
        }
        return List.copyOf(hosts.values());
    }

    public synchronized Map<String, List<Incident>> activeIncidents(List<String> hostIds) {
        return activeIncidents(hostIds, false);
    }

    public synchronized Map<String, List<Incident>> activeIncidents(List<String> hostIds, boolean includeDisabled) {
        var result = new HashMap<String, List<Incident>>();
        if (hostIds.isEmpty()) return result;
        detectVersion();
        if (session == null) login();
        var params = new LinkedHashMap<String, Object>();
        params.put("hostids", hostIds);
        params.put("output", List.of("hostid"));
        if (!includeDisabled) params.put("filter", Map.of("status", "0"));
        var visible = authenticatedRequest("host.get", params);
        visible.forEach(h -> result.put(h.get("hostid").asText(), new ArrayList<>()));
        if (result.isEmpty()) return result;
        var problems = authenticatedRequest("problem.get", Map.of("hostids", result.keySet(),
            "source", 0, "object", 0, "recent", false, "selectAcknowledges", List.of("message"), "output", List.of("eventid", "objectid", "name", "severity", "clock", "r_eventid", "acknowledged")));
        var byTrigger = new HashMap<String, List<Incident>>();
        for (var problem : problems) {
            if (!problem.path("r_eventid").asText("0").equals("0")) continue;
            int messages = messageCount(problem);
            byTrigger.computeIfAbsent(problem.get("objectid").asText(), key -> new ArrayList<>())
                .add(new Incident(problem.get("name").asText(), problem.get("severity").asInt(),
                    problem.path("eventid").asText(""), problem.path("clock").asLong() * 1000, problem.path("acknowledged").asInt() == 1, messages > 0, messages));
        }
        if (!byTrigger.isEmpty()) {
            var triggers = authenticatedRequest("trigger.get", Map.of("triggerids", byTrigger.keySet(),
                "output", List.of("triggerid"), "selectHosts", List.of("hostid")));
            for (var trigger : triggers) {
                var incidents = byTrigger.remove(trigger.get("triggerid").asText());
                for (var host : trigger.get("hosts")) {
                    var list = result.get(host.get("hostid").asText());
                    if (list != null) list.addAll(incidents);
                }
            }
            if (!byTrigger.isEmpty()) throw new IllegalStateException("Incidentes sem host identificado");
        }
        return result;
    }

    private static int messageCount(JsonNode event) {
        int count = 0;
        for (var update : event.path("acknowledges")) {
            if (!update.path("message").asText("").isBlank()) count++;
        }
        return count;
    }

    public record IncidentUpdate(long timestamp, String user, int action, String message,
                                 int oldSeverity, int newSeverity) {}
    public record IncidentDetails(String eventId, String name, int severity, boolean acknowledged,
                                  boolean closed, boolean closing, boolean writable, boolean manualClose,
                                  List<IncidentUpdate> history, String triggerId, long timestamp,
                                  List<Map<String, String>> hosts, String operationalData,
                                  String expression, List<Map<String, String>> tags, ItemReading latestReading) {
        public IncidentDetails(String eventId, String name, int severity, boolean acknowledged,
                               boolean closed, boolean closing, boolean writable, boolean manualClose,
                               List<IncidentUpdate> history) {
            this(eventId, name, severity, acknowledged, closed, closing, writable, manualClose,
                history, "", 0, List.of(), "", "", List.of(), null);
        }
    }

    public record ItemReading(String itemId, String name, String value, String units, long collectedAt) {}

    public synchronized IncidentDetails incidentDetails(String eventId, List<String> hostIds) {
        return incidentDetails(eventId, hostIds, true);
    }

    private IncidentDetails incidentDetails(String eventId, List<String> hostIds, boolean includeReading) {
        if (hostIds.isEmpty()) return null;
        detectVersion();
        if (session == null) login();
        var events = authenticatedRequest("event.get", Map.of("eventids", List.of(eventId),
            "hostids", hostIds, "source", 0, "object", 0, "value", 1,
            "output", List.of("eventid", "objectid", "name", "severity", "acknowledged", "r_eventid", "clock", "opdata"),
            "selectHosts", List.of("hostid", "name"), "selectTags", "extend",
            legacyEvents ? "select_acknowledges" : "selectAcknowledges", "extend"));
        if (events.isEmpty()) return null;
        var event = events.get(0);
        String triggerId = event.path("objectid").asText();
        var triggers = authenticatedRequest("trigger.get", Map.of("triggerids", List.of(triggerId),
            "output", List.of("triggerid", "manual_close", "expression"), "expandExpression", true));
        var editable = authenticatedRequest("trigger.get", Map.of("triggerids", List.of(triggerId),
            "editable", true, "output", List.of("triggerid")));
        var history = new ArrayList<IncidentUpdate>();
        boolean closing = false;
        for (var update : event.path("acknowledges")) {
            int action = update.path("action").asInt();
            closing |= (action & 1) != 0;
            String user = (update.path("name").asText("") + " " + update.path("surname").asText("")).strip();
            if (user.isEmpty()) user = update.path("username").asText("");
            if (user.isEmpty()) user = "Usuário Zabbix #" + update.path("userid").asText("?");
            history.add(new IncidentUpdate(update.path("clock").asLong() * 1000, user, action,
                update.path("message").asText(""), update.path("old_severity").asInt(),
                update.path("new_severity").asInt()));
        }
        history.sort(Comparator.comparingLong(IncidentUpdate::timestamp).reversed());
        String operationalData = event.path("opdata").asText("");
        // The API resolves opdata macros. Never substitute current item values into event data.
        if (includeReading && operationalData.isBlank()) {
            try {
                var problems = authenticatedRequest("problem.get", Map.of("eventids", List.of(eventId),
                    "hostids", hostIds, "output", List.of("eventid", "opdata"), "recent", true));
                for (var problem : problems) {
                    if (eventId.equals(problem.path("eventid").asText())) {
                        operationalData = problem.path("opdata").asText("");
                        break;
                    }
                }
            } catch (RuntimeException ignored) {
                // Optional operational context must not disable incident updates.
            }
        }
        if (operationalData.matches("(?s).*\\{(?:ITEM|HOST|TRIGGER|EVENT)[.][^}]+}.*")) operationalData = "";
        ItemReading latestReading = includeReading ? latestIncidentReading(triggerId, hostIds) : null;
        return new IncidentDetails(eventId, event.path("name").asText(), event.path("severity").asInt(),
            event.path("acknowledged").asInt() == 1, !event.path("r_eventid").asText("0").equals("0"),
            closing, !editable.isEmpty(), !triggers.isEmpty() && triggers.get(0).path("manual_close").asInt() == 1,
            List.copyOf(history), triggerId, event.path("clock").asLong() * 1000,
            stringPairs(event.path("hosts"), "hostid", "name"), operationalData,
            triggers.isEmpty() ? "" : triggers.get(0).path("expression").asText(""),
            stringPairs(event.path("tags"), "tag", "value"), latestReading);
    }

    private ItemReading latestIncidentReading(String triggerId, List<String> allowedHosts) {
        try {
            // Inspect all trigger items before restricting hosts: a cross-host expression is ambiguous too.
            var items = authenticatedRequest("item.get", Map.of("triggerids", List.of(triggerId),
                "output", List.of("itemid", "hostid", "name", "lastvalue", "lastclock", "units"), "limit", 2));
            if (items.size() != 1) return null;
            var item = items.get(0);
            long clock = item.path("lastclock").asLong();
            if (!allowedHosts.contains(item.path("hostid").asText()) || clock <= 0
                    || !item.hasNonNull("lastvalue") || item.path("lastvalue").asText().isBlank()) return null;
            return new ItemReading(item.path("itemid").asText(), item.path("name").asText(),
                item.path("lastvalue").asText(), item.path("units").asText(""), clock * 1000);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private List<Map<String, String>> stringPairs(com.fasterxml.jackson.databind.JsonNode nodes, String key, String value) {
        var pairs = new ArrayList<Map<String, String>>();
        for (var node : nodes) pairs.add(Map.of(key, node.path(key).asText(), value, node.path(value).asText()));
        return List.copyOf(pairs);
    }

    public record TriggerOccurrence(String eventId, long timestamp, Long recoveredAt,
                                    boolean resolved, boolean acknowledged, int messageCount) {}

    public synchronized List<TriggerOccurrence> incidentOccurrences(String eventId, List<String> allowedHosts) {
        var current = incidentDetails(eventId, allowedHosts, false);
        if (current == null) return null;
        var hosts = current.hosts().stream().map(h -> h.get("hostid")).filter(allowedHosts::contains).toList();
        if (hosts.isEmpty() || current.triggerId().isBlank()) return List.of();
        var params = new LinkedHashMap<String, Object>();
        params.put("objectids", List.of(current.triggerId()));
        params.put("hostids", hosts);
        params.put("source", 0);
        params.put("object", 0);
        params.put("value", 1);
        params.put("output", List.of("eventid", "clock", "r_eventid", "acknowledged"));
        params.put(legacyEvents ? "select_acknowledges" : "selectAcknowledges", List.of("message"));
        params.put("sortfield", List.of("clock", "eventid"));
        params.put("sortorder", "DESC");
        params.put("limit", 20);
        var events = authenticatedRequest("event.get", params);
        var recoveryIds = new ArrayList<String>();
        for (var event : events) {
            var id = event.path("r_eventid").asText("0");
            if (!id.equals("0") && !id.isBlank()) recoveryIds.add(id);
        }
        var recoveries = new HashMap<String, Long>();
        if (!recoveryIds.isEmpty()) {
            for (var recovery : authenticatedRequest("event.get", Map.of("eventids", recoveryIds,
                    "output", List.of("eventid", "clock")))) {
                long clock = recovery.path("clock").asLong();
                if (clock > 0) recoveries.put(recovery.path("eventid").asText(), clock * 1000);
            }
        }
        var result = new ArrayList<TriggerOccurrence>();
        for (var event : events) {
            String recoveryId = event.path("r_eventid").asText("0");
            result.add(new TriggerOccurrence(event.path("eventid").asText(), event.path("clock").asLong() * 1000,
                recoveries.get(recoveryId), !recoveryId.equals("0") && !recoveryId.isBlank(),
                event.path("acknowledged").asInt() == 1, messageCount(event)));
        }
        return List.copyOf(result);
    }

    // Mutations return an object, unlike the array returned by the read methods.
    // Only an explicit expired-session response is safe to retry; never retry transport failures.
    public synchronized void updateIncident(String eventId, int action, String message, Integer severity) {
        var params = new LinkedHashMap<String, Object>();
        params.put("eventids", List.of(eventId));
        params.put("action", action);
        params.put("message", message);
        if (severity != null) params.put("severity", severity);
        if (session == null) login();
        var root = request("event.acknowledge", params, session);
        if (expired(root)) {
            session = null;
            login();
            root = request("event.acknowledge", params, session);
        }
        if (root.has("error")) throw new IllegalStateException("Atualização recusada pelo Zabbix. Verifique as permissões da integração.");
        boolean found = false;
        for (var id : root.path("result").path("eventids")) found |= eventId.equals(id.asText());
        if (!found) throw new IllegalStateException("O Zabbix não confirmou a atualização. Consulte o histórico antes de tentar novamente.");
    }

    public synchronized Map<String, Map<String, String>> diagnosticInventory(List<String> ids) {
        var result = new HashMap<String, Map<String, String>>();
        if (ids.isEmpty()) return result;
        detectVersion();
        if (session == null) login();
        var rows = authenticatedRequest("host.get", Map.of("hostids", ids,
            "output", List.of("hostid"), "selectInventory", List.of("model", "os", "software")));
        for (var row : rows) {
            var inventory = row.path("inventory");
            String version = inventory.path("software").asText("");
            if (version.isBlank()) version = inventory.path("os").asText("");
            result.put(row.path("hostid").asText(), Map.of("model", inventory.path("model").asText(""), "version", version));
        }
        return result;
    }

    public synchronized List<ManagedHost.Choice> hostTemplates() {
        detectVersion();
        if (session == null) login();
        var result = new ArrayList<ManagedHost.Choice>();
        for (var row : authenticatedRequest("template.get", Map.of("output", List.of("templateid", "name"))))
            result.add(new ManagedHost.Choice(row.path("templateid").asText(), row.path("name").asText()));
        result.sort(Comparator.comparing(ManagedHost.Choice::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(result);
    }

    private JsonNode configurationRows(String id) {
        detectVersion();
        if (session == null) login();
        var params = new LinkedHashMap<String, Object>();
        params.put("output", List.of("hostid", "host", "name", "status", "flags"));
        params.put("editable", true);
        params.put(legacyGroups ? "selectGroups" : "selectHostGroups", List.of("groupid", "name"));
        params.put("selectParentTemplates", List.of("templateid", "name"));
        params.put("selectInterfaces", "extend");
        if (id != null) params.put("hostids", List.of(id));
        return authenticatedRequest("host.get", params);
    }

    private String configurationRevision(JsonNode row) {
        // Availability/error counters change during polling and are not configuration edits.
        var stable = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        for (String key : List.of("hostid", "host", "name", "status", "flags")) stable.set(key, row.path(key));
        for (String key : List.of(legacyGroups ? "groups" : "hostgroups", "parentTemplates", "interfaces")) {
            var nodes = new ArrayList<JsonNode>();
            row.path(key).forEach(nodes::add);
            String identity = key.equals("interfaces") ? "interfaceid" : key.equals("parentTemplates") ? "templateid" : "groupid";
            nodes.sort(Comparator.comparing(node -> node.path(identity).asText()));
            var array = stable.putArray(key);
            for (var node : nodes) {
                if (!key.equals("interfaces")) { array.add(node.path(identity).asText()); continue; }
                var face = array.addObject();
                for (String field : List.of("interfaceid", "type", "main", "useip", "ip", "dns", "port", "details"))
                    if (node.has(field)) face.set(field, node.get(field));
            }
        }
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(stable.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    private JsonNode primarySnmp(JsonNode row) {
        JsonNode first = null;
        for (var face : row.path("interfaces")) {
            if (face.path("type").asInt() != 2) continue;
            if (face.path("main").asInt() == 1) return face;
            if (first == null) first = face;
        }
        return first;
    }

    public synchronized List<ManagedHost> managedHosts() {
        var result = new ArrayList<ManagedHost>();
        var rows = configurationRows(null);
        var hostIds = new ArrayList<String>();
        rows.forEach(row -> hostIds.add(row.path("hostid").asText()));
        var itemCounts = new HashMap<String, Integer>();
        var triggerCounts = new HashMap<String, Integer>();
        // Fetch minimal fields in batches, rather than making two requests for every host.
        for (int offset = 0; offset < hostIds.size(); offset += 500) {
            var batch = hostIds.subList(offset, Math.min(offset + 500, hostIds.size()));
            for (var item : authenticatedRequest("item.get", Map.of("hostids", batch,
                    "output", List.of("hostid"), "monitored", true)))
                itemCounts.merge(item.path("hostid").asText(), 1, Integer::sum);
            for (var trigger : authenticatedRequest("trigger.get", Map.of("hostids", batch,
                    "output", List.of("triggerid"), "selectHosts", List.of("hostid"), "monitored", true))) {
                var counted = new HashSet<String>();
                for (var host : trigger.path("hosts")) {
                    String hostId = host.path("hostid").asText();
                    if (batch.contains(hostId) && counted.add(hostId)) triggerCounts.merge(hostId, 1, Integer::sum);
                }
            }
        }
        for (var row : rows) {
            var groups = new ArrayList<ManagedHost.Choice>();
            for (var group : row.path(legacyGroups ? "groups" : "hostgroups"))
                groups.add(new ManagedHost.Choice(group.path("groupid").asText(), group.path("name").asText()));
            var templates = new ArrayList<ManagedHost.Choice>();
            for (var template : row.path("parentTemplates"))
                templates.add(new ManagedHost.Choice(template.path("templateid").asText(), template.path("name").asText()));
            var face = primarySnmp(row);
            int version = face == null ? 2 : face.path("details").path("version").asInt();
            var address = face;
            if (address == null) {
                for (var candidate : row.path("interfaces")) {
                    if (address == null || candidate.path("main").asInt() == 1) address = candidate;
                    if (candidate.path("main").asInt() == 1) break;
                }
            }
            String availability = face == null ? "none" : row.path("status").asInt() != 0 ? "disabled"
                : switch (face.path("available").asInt()) { case 1 -> "available"; case 2 -> "unavailable"; default -> "unknown"; };
            result.add(new ManagedHost(row.path("hostid").asText(), row.path("host").asText(),
                row.path("name").asText(), List.copyOf(groups), List.copyOf(templates),
                face == null ? "" : face.path("interfaceid").asText(), address == null ? "" : address.path("ip").asText(),
                face == null ? "" : face.path("dns").asText(), face == null || face.path("useip").asInt() == 1,
                face == null ? "161" : face.path("port").asText(), version,
                face == null ? "{$SNMP_COMMUNITY}" : face.path("details").path("community").asText(),
                row.path("status").asInt() == 0, configurationRevision(row),
                row.path("flags").asInt() == 0 && (version == 1 || version == 2),
                row.path("status").asInt() == 0 ? itemCounts.getOrDefault(row.path("hostid").asText(), 0) : 0,
                row.path("status").asInt() == 0 ? triggerCounts.getOrDefault(row.path("hostid").asText(), 0) : 0,
                availability));
        }
        result.sort(Comparator.comparing(ManagedHost::hostname, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(result);
    }

    public synchronized void deleteManagedHost(String id, String revision) {
        if (id == null || !id.matches("[1-9][0-9]*")) hostInvalid("Host inválido.");
        var rows = configurationRows(id);
        if (rows.isEmpty()) throw new org.springframework.web.server.ResponseStatusException(
            org.springframework.http.HttpStatus.NOT_FOUND, "Host não encontrado ou sem permissão de exclusão.");
        var host = rows.get(0);
        if (!configurationRevision(host).equals(revision))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
                "Host alterado no Zabbix. Atualize a página antes de excluir.");
        if (host.path("flags").asInt() != 0) hostInvalid("Hosts descobertos devem ser gerenciados no Zabbix.");
        var root = request("host.delete", List.of(id), session);
        // Retry only an explicit expired session, never an ambiguous transport failure.
        if (expired(root)) { session = null; login(); root = request("host.delete", List.of(id), session); }
        if (root.has("error")) throw new org.springframework.web.server.ResponseStatusException(
            org.springframework.http.HttpStatus.BAD_GATEWAY, "Zabbix recusou a exclusão. Verifique as permissões da integração.");
        var ids = root.path("result").path("hostids");
        if (!ids.isArray() || ids.size() != 1 || !id.equals(ids.get(0).asText()))
            throw new IllegalStateException("O Zabbix não confirmou a exclusão. Atualize a página antes de tentar novamente.");
    }

    private void hostInvalid(String message) {
        throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, message);
    }

    public synchronized String saveManagedHost(String id, ManagedHost.Input input) {
        if (input == null) { hostInvalid("Informe os dados do host."); }
        String hostname = Objects.requireNonNullElse(input.hostname(), "").strip();
        String visible = Objects.requireNonNullElse(input.visibleName(), "").strip();
        if (hostname.isEmpty() || hostname.length() > 128 || !hostname.matches("[A-Za-z0-9_. -]+"))
            hostInvalid("Nome do host inválido. Use até 128 letras sem acentos, números, espaços, ponto, hífen ou sublinhado.");
        if (visible.length() > 128) hostInvalid("Nome visível deve ter até 128 caracteres.");
        if (input.groupIds() == null || input.groupIds().isEmpty()) hostInvalid("Selecione pelo menos um grupo.");
        var templates = input.templateIds() == null ? List.<String>of() : input.templateIds();
        if (java.util.stream.Stream.concat(input.groupIds().stream(), templates.stream())
                .anyMatch(value -> value == null || !value.matches("[1-9][0-9]*"))) hostInvalid("Grupo ou template inválido.");
        if (input.snmpVersion() != 1 && input.snmpVersion() != 2) hostInvalid("Use SNMPv1 ou SNMPv2c nesta versão.");
        if (input.community() == null || input.community().isBlank() || input.community().length() > 255)
            hostInvalid("Informe a comunidade SNMP, com até 255 caracteres. Macros Zabbix são aceitas.");
        String ip = Objects.requireNonNullElse(input.ip(), "").strip();
        String dns = Objects.requireNonNullElse(input.dns(), "").strip();
        if ((input.useIp() ? ip : dns).isEmpty() || ip.length() > 64 || dns.length() > 255)
            hostInvalid("Informe o endereço IP ou DNS da interface SNMP.");
        if (input.port() == null || !input.port().matches("[0-9]{1,5}")
                || Integer.parseInt(input.port()) < 1 || Integer.parseInt(input.port()) > 65535)
            hostInvalid("Porta deve estar entre 1 e 65535.");
        JsonNode existing = null;
        if (id != null) {
            if (!id.matches("[1-9][0-9]*")) hostInvalid("Host inválido.");
            var rows = configurationRows(id);
            if (rows.isEmpty()) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Host não encontrado ou sem permissão de edição.");
            existing = rows.get(0);
            if (!configurationRevision(existing).equals(input.revision()))
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "Host alterado no Zabbix. Feche o formulário e atualize a lista antes de editar.");
            var snmp = primarySnmp(existing);
            if (existing.path("flags").asInt() != 0 || (snmp != null && snmp.path("details").path("version").asInt() == 3))
                hostInvalid("Hosts descobertos e interfaces SNMPv3 devem ser editados no Zabbix.");
        }
        var face = new LinkedHashMap<String, Object>();
        face.put("type", 2); face.put("main", 1); face.put("useip", input.useIp() ? 1 : 0);
        face.put("ip", ip); face.put("dns", dns); face.put("port", input.port());
        var details = new LinkedHashMap<String, Object>();
        details.put("version", input.snmpVersion()); details.put("community", input.community()); details.put("bulk", 1);
        var interfaces = new ArrayList<Map<String, Object>>();
        if (existing != null) {
            var primary = primarySnmp(existing);
            for (var other : existing.path("interfaces")) {
                if (primary != null && other.path("interfaceid").equals(primary.path("interfaceid"))) {
                    face.put("interfaceid", other.path("interfaceid").asText());
                    face.put("main", other.path("main").asInt());
                    // Preserve version-specific details, including bulk and newer Zabbix tuning fields.
                    other.path("details").fields().forEachRemaining(e -> {
                        if (!Set.of("version", "community").contains(e.getKey())) details.put(e.getKey(), e.getValue());
                    });
                } else {
                    var preserved = new LinkedHashMap<String, Object>();
                    for (String field : List.of("interfaceid", "type", "main", "useip", "ip", "dns", "port", "details"))
                        if (other.has(field) && (!field.equals("details") || other.path("type").asInt() == 2))
                            preserved.put(field, other.get(field));
                    interfaces.add(preserved);
                }
            }
        }
        face.put("details", details); interfaces.add(face);
        var params = new LinkedHashMap<String, Object>();
        params.put("host", hostname); params.put("name", visible.isEmpty() ? hostname : visible);
        params.put("status", input.active() ? 0 : 1);
        params.put("groups", input.groupIds().stream().distinct().map(value -> Map.of("groupid", value)).toList());
        params.put("templates", templates.stream().distinct().map(value -> Map.of("templateid", value)).toList());
        params.put("interfaces", interfaces);
        if (id != null) params.put("hostid", id);
        detectVersion();
        if (session == null) login();
        String method = id == null ? "host.create" : "host.update";
        // Do not retry ambiguous transport failures: host.create may already have committed.
        var root = request(method, params, session);
        if (expired(root)) { session = null; login(); root = request(method, params, session); }
        if (root.has("error")) throw new org.springframework.web.server.ResponseStatusException(
            org.springframework.http.HttpStatus.BAD_GATEWAY,
            "Zabbix recusou o cadastro. Verifique nome duplicado, endereço, grupos, templates e permissões da integração.");
        var ids = root.path("result").path("hostids");
        if (!ids.isArray() || ids.size() != 1 || !ids.get(0).asText().matches("[1-9][0-9]*")
                || (id != null && !id.equals(ids.get(0).asText())))
            throw new IllegalStateException("Zabbix não confirmou o host.");
        return ids.get(0).asText();
    }

    public boolean configured() {
        return !url.isBlank() && !username.isBlank() && !password.isBlank();
    }

    private JsonNode request(String method, Object params, String authentication) {
        var request = client
            .post()
            .uri(url)
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON);
        Map<String, Object> body = new LinkedHashMap<>(
            Map.of("jsonrpc", "2.0", "id", 1, "method", method, "params", params)
        );
        if (authentication != null) {
            if (legacyAuth) body.put("auth", authentication);
            else request.header("Authorization", "Bearer " + authentication);
        }
        JsonNode root = request.body(body).retrieve().body(JsonNode.class);
        if (root == null) throw new IllegalStateException("Resposta vazia da API Zabbix.");
        return root;
    }

    private void detectVersion() {
        if (versionDetected) return;
        var root = request("apiinfo.version", Map.of(), null);
        String[] parts = root.path("result").asText().split("\\.");
        try {
            int major = Integer.parseInt(parts[0]),
                minor = Integer.parseInt(parts[1]);
            if (major < 6) throw new IllegalArgumentException();
            legacyAuth = major == 6 && minor < 4;
            legacyGroups = major == 6 && minor < 2;
            legacyEvents = major < 7;
        } catch (RuntimeException ex) {
            throw new IllegalStateException(
                "Versão da API Zabbix inválida ou não suportada; use Zabbix 6.0 ou superior."
            );
        }
        versionDetected = true;
    }

    private void login() {
        var root = request("user.login", Map.of("username", username, "password", password), null);
        if (
            root.has("error") ||
            !root.path("result").isTextual() ||
            root.path("result").asText().isBlank()
        ) throw new IllegalStateException(
            "Falha no login Zabbix; verifique usuário, senha e acesso à API."
        );
        session = root.path("result").asText();
    }

    private boolean expired(JsonNode root) {
        String data = root.path("error").path("data").asText().toLowerCase(Locale.ROOT);
        return (
            data.contains("session terminated") ||
            data.contains("not authorized") ||
            data.contains("re-login")
        );
    }

    private JsonNode authenticatedRequest(String method, Object params) {
        var root = request(method, params, session);
        if (expired(root)) {
            session = null;
            login();
            root = request(method, params, session);
            if (expired(root)) session = null;
        }
        if (root.has("error") || !root.path("result").isArray()) throw new IllegalStateException(
            "Resposta inválida da API Zabbix; verifique versão e permissões."
        );
        return root.path("result");
    }

    static boolean isIcmpProblem(String name) {
        return Set.of(
            "high icmp ping loss",
            "high icmp ping response time",
            "unavailable by icmp ping"
        ).contains(name.strip().toLowerCase(Locale.ROOT));
    }

    public synchronized List<Host> fetch() {
        return fetchInventory(null, null, true);
    }

    private List<Host> fetchInventory(Set<String> groupIds, Set<String> hostIds, boolean operational) {
        detectVersion();
        if (session == null) login();
        Map<String, Object> params = new LinkedHashMap<>(Map.of(
            "output",
            List.of("hostid", "host", "name"),
            legacyGroups ? "selectGroups" : "selectHostGroups",
            List.of("groupid", "name"),
            "selectInterfaces",
            List.of("ip", "main", "type"),
            "filter",
            Map.of("status", "0")
        ));
        if (groupIds != null) params.put("groupids", groupIds);
        if (hostIds != null) params.put("hostids", hostIds);
        JsonNode inventory = authenticatedRequest("host.get", params);
        Set<String> acknowledgedHosts = new HashSet<>();
        if (operational && !inventory.isEmpty()) {
            List<String> inventoryIds = new ArrayList<>();
            inventory.forEach(h -> inventoryIds.add(h.path("hostid").asText()));
            var triggers = authenticatedRequest(
                "trigger.get",
                Map.of(
                    "output",
                    List.of("triggerid", "description"),
                    "hostids",
                    inventoryIds,
                    "filter",
                    Map.of("value", "1"),
                    "monitored",
                    true,
                    "selectHosts",
                    List.of("hostid"),
                    "selectLastEvent",
                    List.of("acknowledged", "name")
                )
            );
            for (var trigger : triggers) {
                var event = trigger.path("lastEvent");
                if (
                    event.path("acknowledged").asText().equals("1") &&
                    isIcmpProblem(event.path("name").asText(trigger.path("description").asText()))
                ) trigger
                    .path("hosts")
                    .forEach(h -> acknowledgedHosts.add(h.path("hostid").asText()));
            }
        }
        List<Host> hosts = new ArrayList<>();
        for (JsonNode h : inventory) {
            List<JsonNode> interfaces = new ArrayList<>();
            h.path("interfaces").forEach(interfaces::add);
            interfaces.sort(
                Comparator.comparingInt(
                    i ->
                        (i.path("main").asText().equals("1") ? 0 : 2) +
                        (i.path("type").asText().equals("1") ? 0 : 1)
                )
            );
            String ip = interfaces
                .stream()
                .map(i -> i.path("ip").asText())
                .filter(s -> !s.isBlank())
                .findFirst()
                .orElse("");
            List<Host.Group> groups = new ArrayList<>();
            for (JsonNode g : h.path(legacyGroups ? "groups" : "hostgroups"))
                groups.add(new Host.Group(g.path("groupid").asText(), g.path("name").asText()));
            if (groups.isEmpty()) groups.add(new Host.Group("ungrouped", "Sem grupo"));
            hosts.add(
                new Host(
                    h.path("hostid").asText(),
                    h.path("name").asText(h.path("host").asText()),
                    ip,
                    List.copyOf(groups),
                    acknowledgedHosts.contains(h.path("hostid").asText()),
                    h.path("host").asText()
                )
            );
        }
        return List.copyOf(hosts);
    }

    @jakarta.annotation.PreDestroy
    public synchronized void logout() {
        if (session == null) return;
        try {
            request("user.logout", List.of(), session);
        } catch (Exception ignored) {
            /* Shutdown must finish even if Zabbix is unavailable. */
        } finally {
            session = null;
        }
    }
}
