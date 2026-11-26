package br.com.scriptping.backup;

import br.com.scriptping.hosts.HostRepository;
import br.com.scriptping.hosts.HostSecrets;
import br.com.scriptping.hosts.ManagedHost;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
class BackupStore {
    private static final String COLUMNS = "id, host_id, host_name, manufacturer, filename, is_text, created_at";
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final HostRepository hosts;
    private final HostSecrets secrets;

    record Settings(int retention, List<String> hostIds) {}
    record HostOption(String id, String name, String ip, List<ManagedHost.Choice> groups, boolean ready) {}
    record Entry(String id, String hostId, String hostName, String manufacturer, String filename, boolean text, long createdAt) {}
    record File(Entry entry, byte[] content) {}

    BackupStore(JdbcTemplate jdbc, ObjectMapper json, HostRepository hosts, HostSecrets secrets) {
        this.jdbc = jdbc;
        this.json = json;
        this.hosts = hosts;
        this.secrets = secrets;
    }

    List<HostOption> inventory() {
        var credentials = hosts.credentials();
        return hosts.all().stream().map(host -> {
            var saved = credentials.get(host.id());
            boolean ready = saved != null && BackupCapture.supports(saved.manufacturer()) &&
                "ACTIVE".equals(saved.status()) && saved.encryptedPassword() != null &&
                Objects.equals(saved.verifiedIp(), host.useIp() ? host.ip() : host.dns());
            return new HostOption(host.id(), host.visibleName(), host.ip(), host.groups(), ready);
        }).sorted(Comparator.comparing(HostOption::name, String.CASE_INSENSITIVE_ORDER)).toList();
    }

    Settings settings() {
        return jdbc.queryForObject("SELECT retention, host_ids FROM backup_settings WHERE id=1",
            (rs, n) -> new Settings(rs.getInt(1), decodeIds(rs.getString(2))));
    }

    private List<String> decodeIds(String value) {
        try {
            return json.readValue(value, new TypeReference<List<String>>() {});
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("Configuração de backup inválida.", ex);
        }
    }

    @Transactional
    public Settings save(Settings input) {
        if (input.retention() < 1 || input.hostIds() == null || input.hostIds().stream().anyMatch(Objects::isNull)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe uma retenção positiva e uma lista de hosts.");
        }
        var ids = new LinkedHashSet<>(input.hostIds());
        var known = hosts.all().stream().map(ManagedHost::id).collect(java.util.stream.Collectors.toSet());
        if (!known.containsAll(ids)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A lista de hosts mudou. Reabra as configurações.");
        }
        jdbc.update("UPDATE backup_settings SET retention=?, host_ids=? WHERE id=1",
            input.retention(), json.valueToTree(ids).toString());
        prune(input.retention(), null);
        return new Settings(input.retention(), List.copyOf(ids));
    }

    @Transactional
    public void insert(String hostId, BackupCapture.Result result) {
        int retention = jdbc.queryForObject("SELECT retention FROM backup_settings WHERE id=1 FOR UPDATE", Integer.class);
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
        String filter = hostId == null ? "" : " WHERE host_id = ?";
        String sql = "DELETE FROM equipment_backup WHERE id IN (SELECT id FROM ("
            + "SELECT id, ROW_NUMBER() OVER (PARTITION BY host_id ORDER BY created_at DESC, id DESC) AS position "
            + "FROM equipment_backup" + filter + ") ranked WHERE position > ?)";
        if (hostId == null) jdbc.update(sql, retention);
        else jdbc.update(sql, hostId, retention);
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
