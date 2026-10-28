package br.com.scriptping;

import com.fasterxml.jackson.databind.JsonNode;
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
    private boolean versionDetected, legacyAuth, legacyGroups;

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
        String normalized = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "")
            .toLowerCase(Locale.ROOT);
        return (
            normalized.contains("packet loss") ||
            normalized.contains("perda de pacote") ||
            normalized.contains("perca de pacote") ||
            normalized.contains("latency") ||
            normalized.contains("latencia") ||
            (normalized.contains("response time") && normalized.contains("icmp")) ||
            normalized.contains("unavailable by icmp ping") ||
            (normalized.contains("indisponivel") && normalized.contains("icmp"))
        );
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
                    acknowledgedHosts.contains(h.path("hostid").asText())
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
