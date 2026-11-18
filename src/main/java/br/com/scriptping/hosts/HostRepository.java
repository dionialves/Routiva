package br.com.scriptping.hosts;

import br.com.scriptping.zabbix.Host;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class HostRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final HostSecrets secrets;
    public HostRepository(JdbcTemplate jdbc, ObjectMapper json, HostSecrets secrets) {
        this.jdbc = jdbc; this.json = json; this.secrets = secrets;
    }
    public record Credentials(String manufacturer, String login, String encryptedPassword, Integer port,
                              boolean enabled, String status, String message, String verifiedIp, boolean tls, Long testedAt) {
        @Override public String toString() { return "Host credentials [redacted]"; }
    }
    private ManagedHost read(String payload) {
        try { return json.readValue(secrets.decrypt(payload), ManagedHost.class); }
        catch (Exception ex) { throw new IllegalStateException("Não foi possível ler o cadastro local de hosts."); }
    }
    public List<ManagedHost> all() {
        return jdbc.query("SELECT payload FROM app_host ORDER BY host_id", (rs, n) -> read(rs.getString(1)));
    }
    public ManagedHost require(String id) {
        return jdbc.query("SELECT payload FROM app_host WHERE host_id=?", (rs,n) -> read(rs.getString(1)), id)
            .stream().findFirst().orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "Host não encontrado no app."));
    }
    public Host target(String id) {
        var h = require(id);
        return new Host(h.id(), h.visibleName(), h.useIp() ? h.ip() : h.dns(),
            h.groups().stream().map(g -> new Host.Group(g.id(), g.name())).toList(), false, h.hostname());
    }
    public void put(ManagedHost host) {
        try {
            String payload = secrets.encrypt(json.writeValueAsString(host));
            if (jdbc.update("UPDATE app_host SET payload=?, synced_at=? WHERE host_id=?", payload, System.currentTimeMillis(), host.id()) == 0)
                jdbc.update("INSERT INTO app_host(host_id,payload,synced_at) VALUES (?,?,?)", host.id(), payload, System.currentTimeMillis());
        } catch (Exception ex) { throw new IllegalStateException("Não foi possível persistir o host."); }
    }
    private Credentials credentials(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Credentials(rs.getString("manufacturer"), rs.getString("api_login"), rs.getString("api_password"),
            (Integer) rs.getObject("api_port"), rs.getBoolean("enabled"), rs.getString("connection_status"),
            rs.getString("connection_message"), rs.getString("verified_ip"), rs.getBoolean("api_tls"), (Long) rs.getObject("tested_at"));
    }
    public Credentials credentials(String id) {
        return jdbc.query("SELECT * FROM host_authentication WHERE host_id=?", (rs,n) -> credentials(rs), id)
            .stream().findFirst().orElse(null);
    }
    public Map<String, Credentials> credentials() {
        var result = new HashMap<String, Credentials>();
        jdbc.query("SELECT * FROM host_authentication", (org.springframework.jdbc.core.RowCallbackHandler) rs ->
            result.put(rs.getString("host_id"), credentials(rs)));
        return result;
    }
    @org.springframework.transaction.annotation.Transactional
    public void configure(String id, Credentials c) {
        if (jdbc.queryForList("SELECT host_id FROM app_host WHERE host_id=? FOR UPDATE", String.class, id).isEmpty())
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Host excluído durante a configuração.");
        jdbc.update("DELETE FROM host_authentication WHERE host_id=?", id);
        jdbc.update("INSERT INTO host_authentication(host_id,manufacturer,api_login,api_password,api_port,enabled,connection_status,connection_message,verified_ip,api_tls,tested_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
            id, c.manufacturer(), c.login(), c.encryptedPassword(), c.port(), c.enabled(), c.status(), c.message(), c.verifiedIp(), c.tls(), c.testedAt());
    }
    @org.springframework.transaction.annotation.Transactional
    public void delete(String id) {
        jdbc.queryForList("SELECT host_id FROM app_host WHERE host_id=? FOR UPDATE", String.class, id);
        removeMapReferences(id);
        jdbc.update("DELETE FROM access_point WHERE host_id=?", id);
        jdbc.update("DELETE FROM host_authentication WHERE host_id=?", id);
        jdbc.update("DELETE FROM app_host WHERE host_id=?", id);
    }
    private void removeMapReferences(String hostId) {
        jdbc.query("SELECT id,layout,revision FROM network_map FOR UPDATE", (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
            try {
                var layout = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(rs.getString("layout"));
                var nodes = layout.withArray("nodes");
                var removed = new HashSet<String>();
                for (int i = nodes.size() - 1; i >= 0; i--) {
                    var node = nodes.get(i);
                    if (hostId.equals(node.path("hostId").asText())) { removed.add(node.path("id").asText()); nodes.remove(i); }
                }
                if (removed.isEmpty()) return;
                var links = layout.withArray("links");
                for (int i = links.size() - 1; i >= 0; i--) {
                    var link = links.get(i);
                    if (removed.contains(link.path("from").asText()) || removed.contains(link.path("to").asText())) links.remove(i);
                }
                jdbc.update("UPDATE network_map SET layout=?,revision=revision+1,updated_at_ms=? WHERE id=? AND revision=?",
                    layout.toString(), System.currentTimeMillis(), rs.getString("id"), rs.getLong("revision"));
            } catch (java.io.IOException ex) { throw new IllegalStateException("Não foi possível remover o host do mapa.", ex); }
        });
    }
    public void raw(String id, com.fasterxml.jackson.databind.JsonNode configuration) {
        jdbc.update("UPDATE app_host SET zabbix_payload=? WHERE host_id=?", secrets.encrypt(configuration.toString()), id);
    }
    public Map<String, ?> options() {
        var rows = jdbc.query("SELECT options_payload FROM host_catalog_state WHERE id=1", (rs,n) -> rs.getString(1));
        if (rows.isEmpty() || rows.getFirst() == null) return Map.of("groups", List.of(), "templates", List.of());
        try { return json.readValue(secrets.decrypt(rows.getFirst()), new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>() {}); }
        catch (Exception ex) { throw new IllegalStateException("Não foi possível ler grupos e templates locais."); }
    }
    public void options(Map<String, ?> options) {
        try { jdbc.update("UPDATE host_catalog_state SET options_payload=? WHERE id=1", secrets.encrypt(json.writeValueAsString(options))); }
        catch (Exception ex) { throw new IllegalStateException("Não foi possível salvar grupos e templates locais."); }
    }
    public Set<String> trackedIds() {
        return new HashSet<>(jdbc.queryForList("SELECT host_id FROM app_host UNION SELECT host_id FROM host_authentication UNION SELECT host_id FROM access_point", String.class));
    }
    public void reset() {
        trackedIds().forEach(this::delete);
        jdbc.update("DELETE FROM host_authentication");
        jdbc.update("DELETE FROM access_point");
    }
    public String source() {
        return jdbc.query("SELECT source_url FROM host_catalog_state WHERE id=1", (rs,n) -> rs.getString(1)).stream().findFirst().orElse(null);
    }
    public void source(String url) {
        if (jdbc.update("UPDATE host_catalog_state SET source_url=? WHERE id=1", url) == 0)
            jdbc.update("INSERT INTO host_catalog_state(id,source_url) VALUES (1,?)", url);
    }
}
