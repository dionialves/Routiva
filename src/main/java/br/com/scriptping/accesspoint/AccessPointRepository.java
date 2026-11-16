package br.com.scriptping.accesspoint;

import br.com.scriptping.zabbix.Host;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AccessPointRepository {
    private final JdbcTemplate jdbc;
    public AccessPointRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record AccessPoint(String hostId, String name, String ip, String manufacturer, String model,
        Integer connectedClients, Long lastCollectedAt) {}
    public List<AccessPoint> all() {
        return jdbc.query("SELECT * FROM access_point ORDER BY LOWER(host_name), host_id", (rs, n) ->
            new AccessPoint(rs.getString("host_id"), rs.getString("host_name"), rs.getString("ip_address"),
                rs.getString("manufacturer"), rs.getString("model"), (Integer) rs.getObject("connected_clients"),
                rs.getObject("last_collected_at") == null ? null : rs.getLong("last_collected_at")));
    }
    public boolean contains(String id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM access_point WHERE host_id = ?", Integer.class, id) > 0;
    }
    public void add(Host host) {
        if (contains(host.id())) return;
        jdbc.update("INSERT INTO access_point(host_id, host_name, ip_address, manufacturer, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
            host.id(), host.name(), host.ip(), null, System.currentTimeMillis(), System.currentTimeMillis());
    }
    public void refresh(Host host) {
        jdbc.update("UPDATE access_point SET host_name = ?, ip_address = ?, updated_at = ? WHERE host_id = ? AND (host_name <> ? OR COALESCE(ip_address, '') <> COALESCE(?, ''))",
            host.name(), host.ip(), System.currentTimeMillis(), host.id(), host.name(), host.ip());
    }
    public void observation(String id, String model, Integer clients, long at) {
        jdbc.update("UPDATE access_point SET model = COALESCE(?, model), connected_clients = COALESCE(?, connected_clients), last_collected_at = ?, updated_at = ? WHERE host_id = ?",
            model, clients, at, System.currentTimeMillis(), id);
    }
    public record Configuration(String manufacturer, String login, String encryptedPassword, Integer port,
        boolean enabled, String status, String message, String verifiedIp, boolean tls) {
        @Override public String toString() { return "AP configuration [redacted]"; }
    }
    private Configuration configuration(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Configuration(rs.getString("manufacturer"), rs.getString("api_login"), rs.getString("api_password"),
            (Integer) rs.getObject("api_port"), rs.getBoolean("enabled"), rs.getString("connection_status"),
            rs.getString("connection_message"), rs.getString("verified_ip"), rs.getBoolean("api_tls"));
    }
    public Map<String, Configuration> configurations() {
        var result = new HashMap<String, Configuration>();
        jdbc.query("SELECT * FROM access_point", (org.springframework.jdbc.core.RowCallbackHandler) rs ->
            result.put(rs.getString("host_id"), configuration(rs)));
        return result;
    }
    public Configuration configuration(String id) {
        return jdbc.query("SELECT * FROM access_point WHERE host_id = ?", (rs, n) -> configuration(rs), id)
            .stream().findFirst().orElse(null);
    }
    public void configure(String id, Configuration c) {
        jdbc.update("UPDATE access_point SET manufacturer=?, api_login=?, api_password=?, api_port=?, enabled=?, connection_status=?, connection_message=?, verified_ip=?, api_tls=?, updated_at=? WHERE host_id=?",
            c.manufacturer(), c.login(), c.encryptedPassword(), c.port(), c.enabled(), c.status(), c.message(), c.verifiedIp(), c.tls(), System.currentTimeMillis(), id);
    }
    public void connectionState(String id, String status, String message, String ip) {
        jdbc.update("UPDATE access_point SET connection_status=?, connection_message=?, verified_ip=?, updated_at=? WHERE host_id=?",
            status, message, ip, System.currentTimeMillis(), id);
    }
    public void delete(String id) { jdbc.update("DELETE FROM access_point WHERE host_id=?", id); }
}
