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
            host.id(), host.name(), host.ip(), "MikroTik", System.currentTimeMillis(), System.currentTimeMillis());
    }
    public void refresh(Host host) {
        jdbc.update("UPDATE access_point SET host_name = ?, ip_address = ?, updated_at = ? WHERE host_id = ? AND (host_name <> ? OR COALESCE(ip_address, '') <> COALESCE(?, ''))",
            host.name(), host.ip(), System.currentTimeMillis(), host.id(), host.name(), host.ip());
    }
    public void observation(String id, String model, Integer clients, long at) {
        jdbc.update("UPDATE access_point SET model = COALESCE(?, model), connected_clients = COALESCE(?, connected_clients), last_collected_at = ?, updated_at = ? WHERE host_id = ?",
            model, clients, at, System.currentTimeMillis(), id);
    }
}
