package br.com.scriptping.backup;

import br.com.scriptping.hosts.HostRepository;
import br.com.scriptping.hosts.HostSecrets;
import br.com.scriptping.hosts.ManagedHost;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@org.springframework.context.annotation.DependsOn("backupSettingsMigration")
class BackupStore {
    private static final String COLUMNS = "id, host_id, host_name, manufacturer, filename, is_text, created_at";
    private final JdbcTemplate jdbc;
    private final HostRepository hosts;
    private final HostSecrets secrets;

    record Settings(boolean enabled, int retention) {}
    record HostOption(String id, String name, String ip, List<ManagedHost.Choice> groups, boolean ready, boolean backupEnabled) {}
    record Entry(String id, String hostId, String hostName, String manufacturer, String filename, boolean text, long createdAt) {}
    record File(Entry entry, byte[] content) {}

    BackupStore(JdbcTemplate jdbc, HostRepository hosts, HostSecrets secrets) {
        this.jdbc = jdbc;
        this.hosts = hosts;
        this.secrets = secrets;
    }

    List<HostOption> inventory() {
        var credentials = hosts.credentials();
        var enabled = hosts.backupEnabledIds();
        return hosts.all().stream().map(host -> {
            var saved = credentials.get(host.id());
            boolean ready = saved != null && BackupCapture.supports(saved.manufacturer()) &&
                "ACTIVE".equals(saved.status()) && saved.encryptedPassword() != null &&
                Objects.equals(saved.verifiedIp(), host.useIp() ? host.ip() : host.dns());
            return new HostOption(host.id(), host.visibleName(), host.ip(), host.groups(), ready, enabled.contains(host.id()));
        }).sorted(Comparator.comparing(HostOption::name, String.CASE_INSENSITIVE_ORDER)).toList();
    }

    Settings settings(String hostId) {
        return settings(hostId, "");
    }

    private Settings settings(String hostId, String lock) {
        return jdbc.query("SELECT backup_enabled, backup_retention FROM app_host WHERE host_id=?" + lock,
            (rs, n) -> new Settings(rs.getBoolean(1), rs.getInt(2)), hostId).stream().findFirst()
            .orElseThrow(BackupStore::missingHost);
    }

    @Transactional
    public Settings save(String hostId, Settings input) {
        if (input.retention() < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe uma quantidade positiva de backups.");
        }
        if (jdbc.update("UPDATE app_host SET backup_enabled=?, backup_retention=? WHERE host_id=?",
            input.enabled(), input.retention(), hostId) == 0) throw missingHost();
        prune(input.retention(), hostId);
        return input;
    }

    @Transactional
    public void insert(String hostId, BackupCapture.Result result) {
        int retention = settings(hostId, " FOR UPDATE").retention();
        String id = UUID.randomUUID().toString();
        long createdAt = System.currentTimeMillis();
        String slug = result.hostName().replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("^[.-]+", "");
        if (slug.isEmpty()) slug = "backup";
        if (slug.length() > 100) slug = slug.substring(0, 100);
        String filename = slug + "_" + Instant.ofEpochMilli(createdAt).toString().replace(':', '-') + result.extension();
        jdbc.update("INSERT INTO equipment_backup (" + COLUMNS + ", encrypted_content) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            id, hostId, result.hostName(), result.manufacturer(), filename, result.text(), createdAt,
            secrets.encryptBytes(result.content()));
        prune(retention, hostId);
    }

    private void prune(int retention, String hostId) {
        jdbc.update("DELETE FROM equipment_backup WHERE id IN (SELECT id FROM ("
            + "SELECT id, ROW_NUMBER() OVER (ORDER BY created_at DESC, id DESC) AS position "
            + "FROM equipment_backup WHERE host_id=?) ranked WHERE position > ?)", hostId, retention);
    }

    private static ResponseStatusException missingHost() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Host não encontrado.");
    }

    List<Entry> list() {
        return jdbc.query("SELECT " + COLUMNS + " FROM equipment_backup ORDER BY created_at DESC, id DESC", BackupStore::entry);
    }

    File file(String id) {
        return jdbc.query("SELECT " + COLUMNS + ", encrypted_content FROM equipment_backup WHERE id=?",
            (rs, n) -> new File(entry(rs, n), secrets.decryptBytes(rs.getString("encrypted_content"))), id)
            .stream().findFirst().orElseThrow(BackupStore::missing);
    }

    void delete(String id) {
        if (jdbc.update("DELETE FROM equipment_backup WHERE id=?", id) == 0) throw missing();
    }

    private static Entry entry(ResultSet rs, int row) throws SQLException {
        return new Entry(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
            rs.getString(5), rs.getBoolean(6), rs.getLong(7));
    }

    private static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Backup não encontrado.");
    }
}
