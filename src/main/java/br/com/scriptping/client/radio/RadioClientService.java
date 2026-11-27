package br.com.scriptping.client.radio;

import br.com.scriptping.ixc.IxcClient;
import br.com.scriptping.zabbix.ZabbixService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Persistence and identity analysis for captured task collections. Source data remains immutable on relinking. */
@Service
public class RadioClientService {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final ZabbixService inventory;
    private final int minimum;
    private final long coverage;
    public RadioClientService(JdbcTemplate db, TransactionTemplate tx, ObjectMapper json,
            ZabbixService inventory, @Value("${radio.history.minimum-readings:2}") int minimum,
            @Value("${radio.history.minimum-duration-ms:10000}") long coverage) {
        this.db = db; this.tx = tx; this.json = json; this.inventory = inventory;
        this.minimum = Math.max(2, minimum); this.coverage = Math.max(0, coverage);
    }
    /** Only the scheduled task supplies captured data; this service performs no network queries. */
    public void persistCollection(RadioCollection.Batch batch, List<IxcClient.Login> resolved) {
        var samples = batch.samples().stream().filter(sample -> sample.at() >= batch.startedAt()
            && sample.at() <= batch.touchedAt() + 1000).sorted(Comparator.comparingLong(RadioCollection.Sample::at)).toList();
        var macs = new LinkedHashSet<String>(); samples.forEach(sample -> macs.add(IxcClient.normalizeMac(sample.mac())));
        final var identities = List.copyOf(resolved);
        locked(() -> {
            var existing = one("SELECT * FROM radio_visit WHERE id = ?", batch.id());
            if (existing != null && (!batch.host().equals(existing.get("host_id")) || !batch.owner().equals(existing.get("owner_id"))))
                throw new IllegalArgumentException("Visita incompatível com o lote recebido.");
            if (existing == null) db.update("INSERT INTO radio_visit(id, host_id, host_name, owner_id, started_at, touched_at) VALUES (?, ?, ?, ?, ?, ?)",
                batch.id(), batch.host(), batch.hostName(), batch.owner(), batch.startedAt(), batch.touchedAt());
            if (existing == null || !Boolean.TRUE.equals(existing.get("closed"))) {
                for (var sample : samples) {
                    String mac = IxcClient.normalizeMac(sample.mac());
                    if (one("SELECT id FROM radio_observation WHERE visit_id = ? AND mac = ? AND measured_at = ?", batch.id(), mac, sample.at()) != null) continue;
                    var names = sample.observedLogins().stream().filter(Objects::nonNull).map(String::strip).filter(n -> !n.isBlank()).distinct().toList();
                    String observed = names.size() == 1 ? names.getFirst() : null;
                    var linkedNames = names;
                    if (linkedNames.isEmpty()) {
                        // Later knowledge can link an earlier unknown sample without rewriting what was observed.
                        linkedNames = samples.stream().filter(s -> mac.equals(s.mac()) && s.at() >= sample.at() && !s.observedLogins().isEmpty())
                            .map(RadioCollection.Sample::observedLogins).findFirst().orElseGet(() -> identities.stream()
                                .filter(login -> mac.equals(login.mac)).map(login -> login.pppoe.strip()).filter(n -> !n.isBlank()).distinct().toList());
                    }
                    String linkedLogin = linkedNames.size() == 1 ? linkedNames.getFirst() : null;
                    String client = identify(mac, linkedLogin, sample.at(), linkedNames.size() > 1, "IXC", null);
                    var raw = new LinkedHashMap<>(sample.raw()); raw.put("identity-ambiguous", Boolean.toString(linkedNames.size() > 1));
                    db.update("INSERT INTO radio_observation(id, visit_id, client_id, mac, measured_at, observed_login, raw_data) VALUES (?, ?, ?, ?, ?, ?, ?)",
                        id(), batch.id(), client, mac, sample.at(), observed, encode(raw));
                }
                // Identification performed after closing is retrospective, never an observed login.
                // An existing different owner retains the preceding interval; only unowned samples merge.
                long identifiedAt = System.currentTimeMillis();
                for (String mac : macs) {
                    var names = identities.stream().filter(login -> mac.equals(login.mac)).map(login -> login.pppoe.strip()).filter(n -> !n.isBlank()).distinct().toList();
                    if (names.size() == 1) identify(mac, names.getFirst(), identifiedAt, false, "IXC", null);
                }
                finish(batch.id());
            }
            for (var test : batch.tests()) latency(batch.id(), batch.host(), batch.owner(), test.mac(), test.at(), new LinkedHashMap<>(test.data()));
            return null;
        });
    }
    /** Completes visits left in the database by the previous synchronous implementation. */
    public void recoverUnfinishedVisits() {
        rows("SELECT id FROM radio_visit WHERE closed = FALSE").forEach(visit -> finish(visit.get("id").toString()));
    }
    private <T> T locked(Supplier<T> work) {
        return tx.execute(status -> {
            db.queryForObject("SELECT id FROM radio_identity_lock WHERE id = 1 FOR UPDATE", Integer.class);
            return work.get();
        });
    }
    private static String id() { return UUID.randomUUID().toString(); }
    private static long number(Map<String, Object> row, String key) { return ((Number) row.get(key)).longValue(); }
    private List<Map<String, Object>> rows(String sql, Object... args) {
        return db.query(sql, (rs, n) -> {
            var row = new LinkedHashMap<String, Object>();
            for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++)
                row.put(rs.getMetaData().getColumnLabel(i).toLowerCase(Locale.ROOT), rs.getObject(i));
            return row;
        }, args);
    }
    private Map<String, Object> one(String sql, Object... args) { return rows(sql, args).stream().findFirst().orElse(null); }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private Map<String, Object> decode(Object value) {
        try { return json.readValue(value.toString(), new TypeReference<LinkedHashMap<String, Object>>() {}); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    public Set<String> visibleHosts() {
        var result = new HashSet<String>(); inventory.hosts().forEach(h -> result.add(h.id())); return result;
    }
    private String provisional(String mac, long at) {
        var existing = one("SELECT id FROM radio_client WHERE provisional_mac = ?", mac);
        if (existing != null) return existing.get("id").toString();
        String client = id();
        db.update("INSERT INTO radio_client(id, provisional_mac, created_at) VALUES (?, ?, ?)", client, mac, at);
        return client;
    }
    private String identify(String mac, String login, long at, boolean ambiguous, String origin, String operator) {
        var binding = one("SELECT * FROM radio_mac_binding WHERE mac = ? AND valid_from <= ? AND (valid_to IS NULL OR valid_to > ?) ORDER BY valid_from DESC", mac, at, at);
        if (ambiguous) return provisional(mac, at);
        if (login == null) {
            if (binding != null) return binding.get("client_id").toString();
            String client = provisional(mac, at);
            var next = one("SELECT valid_from FROM radio_mac_binding WHERE mac = ? AND valid_from > ? ORDER BY valid_from", mac, at);
            db.update("INSERT INTO radio_mac_binding(id, client_id, mac, valid_from, valid_to, origin, recorded_at) VALUES (?, ?, ?, ?, ?, 'PROVISIONAL', ?)",
                id(), client, mac, at, next == null ? null : next.get("valid_from"), System.currentTimeMillis());
            return client;
        }
        var target = one("SELECT * FROM radio_client WHERE login = ?", login);
        var pending = one("SELECT * FROM radio_client WHERE provisional_mac = ?", mac);
        String client;
        if (target != null) client = target.get("id").toString();
        else if (pending != null && rows("SELECT raw_data FROM radio_observation WHERE client_id = ?", pending.get("id"))
                .stream().noneMatch(o -> "true".equals(decode(o.get("raw_data")).get("identity-ambiguous")))) {
            client = pending.get("id").toString();
            db.update("UPDATE radio_client SET login = ?, provisional_mac = NULL WHERE id = ?", login, client);
        } else {
            client = id(); db.update("INSERT INTO radio_client(id, login, created_at) VALUES (?, ?, ?)", client, login, at);
        }
        if (pending != null && !pending.get("id").equals(client)
                && rows("SELECT raw_data FROM radio_observation WHERE client_id = ?", pending.get("id")).stream()
                    .noneMatch(o -> "true".equals(decode(o.get("raw_data")).get("identity-ambiguous")))) {
            // Only the unowned interval is attributable; never move an identified owner's data.
            long from = binding == null ? 0 : number(binding, "valid_from");
            merge(pending.get("id").toString(), client, mac, from, at);
        }
        if (binding == null || !binding.get("client_id").equals(client) || "PROVISIONAL".equals(binding.get("origin"))) {
            if (binding != null) db.update("UPDATE radio_mac_binding SET valid_to = ? WHERE id = ?", at, binding.get("id"));
            var next = one("SELECT valid_from FROM radio_mac_binding WHERE mac = ? AND valid_from > ? ORDER BY valid_from", mac, at);
            db.update("INSERT INTO radio_mac_binding(id, client_id, mac, valid_from, valid_to, origin, operator_name, recorded_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                id(), client, mac, at, next == null ? null : next.get("valid_from"), origin, operator, System.currentTimeMillis());
        }
        return client;
    }
    private void merge(String source, String target, String mac, long from, long to) {
        var affected = rows("SELECT DISTINCT visit_id FROM radio_observation WHERE client_id = ? AND mac = ? AND measured_at >= ? AND measured_at <= ?", source, mac, from, to);
        db.update("UPDATE radio_observation SET client_id = ? WHERE client_id = ? AND mac = ? AND measured_at >= ? AND measured_at <= ?", target, source, mac, from, to);
        for (var affectedVisit : affected) {
            String visit = affectedVisit.get("visit_id").toString();
            var v = one("SELECT * FROM radio_visit WHERE id = ?", visit);
            if (!Boolean.TRUE.equals(v.get("closed"))) continue;
            // A manual boundary may split a visit. Recompute from immutable source samples.
            db.update("DELETE FROM radio_collection WHERE visit_id = ? AND mac = ? AND (client_id = ? OR client_id = ?)", visit, mac, source, target);
            for (String client : List.of(source, target)) {
                var samples = rows("SELECT * FROM radio_observation WHERE visit_id = ? AND mac = ? AND client_id = ? ORDER BY measured_at", visit, mac, client);
                saveCollection(v, samples);
            }
        }
        if (one("SELECT id FROM radio_observation WHERE client_id = ?", source) == null
                && one("SELECT id FROM radio_collection WHERE client_id = ?", source) == null) {
            db.update("UPDATE radio_mac_binding SET client_id = ? WHERE client_id = ?", target, source);
            db.update("DELETE FROM radio_client WHERE id = ?", source);
        }
    }
    private void finish(String visit) {
        locked(() -> {
            var v = one("SELECT * FROM radio_visit WHERE id = ?", visit);
            if (v == null || Boolean.TRUE.equals(v.get("closed"))) return null;
            var groups = new LinkedHashMap<String, List<Map<String, Object>>>();
            for (var row : rows("SELECT * FROM radio_observation WHERE visit_id = ? ORDER BY measured_at", visit))
                groups.computeIfAbsent(row.get("client_id") + "/" + row.get("mac"), k -> new ArrayList<>()).add(row);
            for (var samples : groups.values()) saveCollection(v, samples);
            db.update("UPDATE radio_visit SET closed = TRUE WHERE id = ?", visit); return null;
        });
    }
    private void saveCollection(Map<String, Object> visit, List<Map<String, Object>> samples) {
        if (samples.size() < minimum) return;
        var first = samples.getFirst(); var last = samples.getLast();
        long start = number(first, "measured_at"), end = number(last, "measured_at");
        if (end - start < coverage) return;
        db.update("INSERT INTO radio_collection(id, visit_id, client_id, host_id, mac, observed_from, observed_to, data) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            id(), visit.get("id"), last.get("client_id"), visit.get("host_id"), last.get("mac"), start, end, encode(collectionData(visit, samples)));
    }
    private Map<String, Object> collectionData(Map<String, Object> visit, List<Map<String, Object>> samples) {
        var data = aggregate(samples); data.put("apName", visit.get("host_name")); return data;
    }
    static Double metric(Object value, boolean rate) {
        if (value == null) return null;
        var matcher = java.util.regex.Pattern.compile("^\\s*(-?\\d+(?:\\.\\d+)?)").matcher(value.toString());
        if (!matcher.find()) return null;
        double result = Double.parseDouble(matcher.group(1));
        if (rate) {
            String unit = value.toString().toLowerCase(Locale.ROOT);
            if (unit.contains("gbps")) result *= 1000;
            else if (unit.contains("kbps")) result /= 1000;
            else if (!unit.contains("mbps")) result /= 1_000_000;
        }
        return Double.isFinite(result) ? result : null;
    }
    private Map<String, Object> aggregate(List<Map<String, Object>> samples) {
        var result = new LinkedHashMap<String, Object>();
        var metrics = new LinkedHashMap<String, Object>();
        for (String key : List.of("signal", "signal-to-noise", "tx-ccq", "p-throughput", "tx-rate", "rx-rate")) {
            double sum = 0; int count = 0;
            for (var sample : samples) {
                var raw = decode(sample.get("raw_data"));
                Object value = raw.get(key);
                if (key.equals("signal") && value == null) value = raw.get("signal-strength");
                // RouterOS p-throughput is kbps; PHY rates have explicit units or bps.
                Double n = metric(value, key.endsWith("-rate"));
                if (n == null || (key.equals("signal") ? n >= 0 || n < -150 : n < 0)
                        || (key.equals("tx-ccq") && n > 100)) continue;
                if (key.equals("p-throughput")) n /= 1000;
                sum += n; count++;
            }
            var metric = new LinkedHashMap<String, Object>(); metric.put("mean", count == 0 ? null : sum / count); metric.put("count", count);
            metrics.put(key, metric);
        }
        result.put("metrics", metrics); result.put("sampleCount", samples.size());
        for (var sample : samples) {
            var raw = decode(sample.get("raw_data"));
            copyDiagnosis(raw, number(sample, "measured_at"), result);
            for (String field : List.of("ip-address", "interface", "distance", "uptime", "radio-name"))
                if (raw.get(field) != null && !raw.get(field).toString().isBlank()) {
                    if (field.equals("distance") && (metric(raw.get(field), false) == null || metric(raw.get(field), false) < 0)) continue;
                    result.put(field, raw.get(field));
                }
        }
        result.put("observedLogin", samples.getLast().get("observed_login"));
        return result;
    }
    private void copyDiagnosis(Map<String, Object> raw, long at, Map<String, Object> data) {
        Double signalSeverity = metric(raw.get("signal-severity"), false);
        data.put("signalSeverity", signalSeverity != null && signalSeverity >= 0 && signalSeverity <= 3 ? signalSeverity.intValue() : null);
        // Preserve the AP's multifactor diagnosis, never infer it from historical signal averages.
        Double severity = metric(raw.get("quality-severity"), false);
        boolean valid = severity != null && severity >= 0 && severity <= 3 && severity == Math.floor(severity);
        data.put("connectionSeverity", valid ? severity.intValue() : null);
        var diagnosis = new LinkedHashMap<String, Object>();
        diagnosis.put("severity", valid ? severity.intValue() : null);
        diagnosis.put("label", raw.get("quality"));
        diagnosis.put("reason", raw.get("quality-primary-reason"));
        diagnosis.put("summary", raw.get("quality-summary"));
        diagnosis.put("at", at);
        data.put("connectionDiagnosis", diagnosis);
    }
    private void latency(String visit, String host, String owner, String mac, long at, Map<String, Object> stats) {
        if (visit == null || mac == null || mac.isBlank()) return;
        locked(() -> {
            if (stats.get("startedAt") instanceof Number start) {
                var observed = one("SELECT measured_at FROM radio_observation WHERE visit_id = ? AND mac = ? AND measured_at <= ? ORDER BY measured_at DESC", visit, mac, start.longValue());
                if (observed == null) return null;
                stats.put("observationAt", observed.get("measured_at"));
            }
            var previous = one("SELECT * FROM radio_latency WHERE visit_id = ? AND mac = ? AND tested_at = ?", visit, mac, at);
            if (previous == null) db.update("INSERT INTO radio_latency(visit_id, mac, tested_at, data) VALUES (?, ?, ?, ?)", visit, mac, at, encode(stats));
            return null;
        });
    }
    private Map<String, Object> collection(Map<String, Object> row) {
        Map<String, Object> source = null;
        var data = decode(row.get("data"));
        if (needsSource(data)) source = one("SELECT raw_data, measured_at FROM radio_observation WHERE visit_id = ? AND client_id = ? AND mac = ? AND measured_at <= ? ORDER BY measured_at DESC LIMIT 1", row.get("visit_id"), row.get("client_id"), row.get("mac"), row.get("observed_to"));
        return collection(row, source, rows("SELECT * FROM radio_latency WHERE visit_id = ? AND mac = ? ORDER BY tested_at DESC", row.get("visit_id"), row.get("mac")));
    }
    private boolean needsSource(Map<String, Object> data) {
        return !data.containsKey("connectionDiagnosis") || !data.containsKey("radio-name") || !data.containsKey("signalSeverity");
    }
    private Map<String, Object> collection(Map<String, Object> row, Map<String, Object> source,
            List<Map<String, Object>> latencies) {
        var result = new LinkedHashMap<>(row);
        var data = decode(row.get("data"));
        if (needsSource(data)) {
            if (source != null) {
                var raw = decode(source.get("raw_data"));
                if (!data.containsKey("connectionDiagnosis") || !data.containsKey("signalSeverity")) copyDiagnosis(raw, number(source, "measured_at"), data);
                if (!data.containsKey("radio-name")) data.put("radio-name", raw.get("radio-name"));
            }
        }
        result.put("data", data);
        for (var latency : latencies) {
            var stats = decode(latency.get("data"));
            Object observation = stats.get("observationAt");
            if (!(observation instanceof Number at) || (at.longValue() >= number(row, "observed_from") && at.longValue() <= number(row, "observed_to"))) {
                latency.put("data", stats); result.put("latency", latency); break;
            }
        }
        return result;
    }
    public long historyCount(String client) {
        var visible = visibleHosts();
        return rows("SELECT host_id FROM radio_collection WHERE client_id = ?", client).stream().filter(row -> visible.contains(row.get("host_id"))).count();
    }
    public List<Map<String, Object>> history(String client, int page) {
        return history(client, page, "observed", true);
    }

    public List<Map<String, Object>> history(String client, int page, String sort, boolean descending) {
        Set<String> visible = visibleHosts();
        if (visible.isEmpty()) return List.of();
        String placeholders = String.join(",", Collections.nCopies(visible.size(), "?"));
        String historyCte = "WITH selected_collections AS (SELECT * FROM radio_collection WHERE client_id = ? AND host_id IN ("
            + placeholders + ")) ";
        var arguments = new ArrayList<Object>();
        arguments.add(client);
        arguments.addAll(visible);
        Object[] params = arguments.toArray();
        var candidates = rows(historyCte + "SELECT * FROM selected_collections ORDER BY observed_to DESC, id DESC", params);
        long offset = (long) Math.max(0, page) * 20;
        if ("observed".equals(sort) && descending) {
            return candidates.stream().skip(offset).limit(20).map(this::collection).toList();
        }
        boolean needsCollection = Set.of("status", "latency", "loss").contains(sort);
        var results = needsCollection ? collectionsWithDetails(historyCte, candidates, params) : candidates;
        List<Map<String, Object>> selected;
        if (Set.of("mac", "ap").contains(sort)) {
            Comparator<String> textOrder = String.CASE_INSENSITIVE_ORDER;
            if (descending) textOrder = textOrder.reversed();
            selected = historyPage(results, row -> historyText(row, sort), textOrder, offset);
        } else {
            Comparator<Double> numberOrder = descending ? Comparator.reverseOrder() : Comparator.naturalOrder();
            selected = historyPage(results, row -> historyNumber(row, sort), numberOrder, offset);
        }
        return needsCollection ? selected : selected.stream().map(this::collection).toList();
    }

    private record HistoryKey<T>(Map<String, Object> row, T key) {}

    private static <T> List<Map<String, Object>> historyPage(List<Map<String, Object>> rows,
            java.util.function.Function<Map<String, Object>, T> key, Comparator<T> order, long offset) {
        return rows.stream().map(row -> new HistoryKey<>(row, key.apply(row)))
            .sorted(Comparator.comparing(HistoryKey::key, Comparator.nullsLast(order)))
            .skip(offset).limit(20).map(HistoryKey::row).toList();
    }

    private String historyText(Map<String, Object> row, String sort) {
        Object text = "mac".equals(sort) ? row.get("mac") : decode(row.get("data")).getOrDefault("apName", row.get("host_id"));
        return text == null || text.toString().isBlank() ? null : text.toString();
    }

    private Double historyNumber(Map<String, Object> row, String sort) {
        Map<?, ?> data = row.get("data") instanceof Map<?, ?> decoded ? decoded : decode(row.get("data"));
        Object value = switch (sort) {
            case "signal", "snr", "ccq" -> {
                String metric = switch (sort) {
                    case "snr" -> "signal-to-noise";
                    case "ccq" -> "tx-ccq";
                    default -> "signal";
                };
                yield data.get("metrics") instanceof Map<?, ?> metrics
                    && metrics.get(metric) instanceof Map<?, ?> stats ? stats.get("mean") : null;
            }
            case "status" -> data.get("connectionSeverity");
            case "latency", "loss" -> row.get("latency") instanceof Map<?, ?> latency
                && latency.get("data") instanceof Map<?, ?> stats ? stats.get(sort) : null;
            default -> row.get("observed_to");
        };
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private List<Map<String, Object>> collectionsWithDetails(String cte, List<Map<String, Object>> collections, Object... params) {
        if (collections.isEmpty()) return List.of();
        // Legacy collections may need their original diagnosis; fetch one source row per collection in a batch.
        var sources = new HashMap<String, Map<String, Object>>();
        for (var source : rows(cte + """
                SELECT source.collection_id, o.raw_data, o.measured_at FROM (
                    SELECT c.id AS collection_id, o.id AS observation_id,
                        ROW_NUMBER() OVER (PARTITION BY c.id ORDER BY o.measured_at DESC, o.id DESC) AS rn
                    FROM selected_collections c JOIN radio_observation o ON o.visit_id = c.visit_id
                        AND o.client_id = c.client_id AND o.mac = c.mac AND o.measured_at <= c.observed_to
                ) source JOIN radio_observation o ON o.id = source.observation_id WHERE source.rn = 1
                """, params)) sources.put(source.get("collection_id").toString(), source);
        var tests = new HashMap<String, List<Map<String, Object>>>();
        for (var test : rows(cte + """
                SELECT c.id AS collection_id, l.* FROM selected_collections c JOIN radio_latency l
                    ON l.visit_id = c.visit_id AND l.mac = c.mac ORDER BY l.tested_at DESC
                """, params)) {
            String collectionId = test.remove("collection_id").toString();
            tests.computeIfAbsent(collectionId, key -> new ArrayList<>()).add(test);
        }
        return collections.stream().map(row -> {
            String id = row.get("id").toString();
            return collection(row, sources.get(id), tests.getOrDefault(id, List.of()));
        }).toList();
    }

    public List<Map<String, Object>> list() {
        var hosts = inventory.hosts();
        if (hosts.isEmpty()) return List.of();
        var hostGroups = new HashMap<String, List<br.com.scriptping.zabbix.Host.Group>>();
        hosts.forEach(host -> hostGroups.put(host.id(), host.groups()));
        Object[] visible = hostGroups.keySet().toArray();
        String placeholders = String.join(",", Collections.nCopies(visible.length, "?"));
        // Rank only visible rows, before selecting the latest record or the first observation date.
        var observations = new HashMap<String, Map<String, Object>>();
        for (var observation : rows("""
                SELECT o.*, v.host_id, v.host_name, ranked.first_at
                FROM (
                    SELECT o.id, MIN(o.measured_at) OVER (PARTITION BY o.client_id) AS first_at,
                        ROW_NUMBER() OVER (PARTITION BY o.client_id ORDER BY o.measured_at DESC, o.id DESC) AS rn
                    FROM radio_observation o JOIN radio_visit v ON v.id = o.visit_id
                    WHERE v.host_id IN (%s)
                ) ranked JOIN radio_observation o ON o.id = ranked.id
                JOIN radio_visit v ON v.id = o.visit_id WHERE ranked.rn = 1
                """.formatted(placeholders), visible)) {
            observations.put(observation.get("client_id").toString(), observation);
        }
        if (observations.isEmpty()) return List.of();
        String latestCte = """
                WITH ranked AS (
                    SELECT id, ROW_NUMBER() OVER (PARTITION BY client_id ORDER BY observed_to DESC, id DESC) AS rn
                    FROM radio_collection WHERE host_id IN (%s)
                ), selected_collections AS (
                    SELECT c.* FROM radio_collection c JOIN ranked r ON r.id = c.id WHERE r.rn = 1
                )
                """.formatted(placeholders);
        var latest = new HashMap<String, Map<String, Object>>();
        var collections = rows(latestCte + "SELECT * FROM selected_collections", visible);
        for (var row : collectionsWithDetails(latestCte, collections, visible)) {
            latest.put(row.get("client_id").toString(), row);
        }
        var result = new ArrayList<Map<String, Object>>();
        for (var client : rows("SELECT * FROM radio_client ORDER BY login, provisional_mac")) {
            String clientId = client.get("id").toString();
            var observation = observations.get(clientId);
            if (observation == null) continue;
            var collection = latest.get(clientId);
            client.put("latest", collection);
            var source = decode(observation.remove("raw_data"));
            observation.put("radioName", source.get("radio-name"));
            observation.put("interface", source.get("interface"));
            client.put("observedFrom", observation.remove("first_at"));
            client.put("lastSeen", observation);
            String displayedHost = (collection == null ? observation.get("host_id") : collection.get("host_id")).toString();
            client.put("groups", hostGroups.getOrDefault(displayedHost, List.of()));
            result.add(client);
        }
        return result;
    }
    public Map<String, Object> manual(String source, String login, long from, String operator, boolean confirm) {
        if (login == null || login.isBlank() || login.strip().length() > 255 || from > System.currentTimeMillis())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe um login e período válidos.");
        return locked(() -> {
            var client = one("SELECT * FROM radio_client WHERE id = ? AND login IS NULL", source);
            if (client == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "O cliente já foi identificado. Atualize a lista.");
            String mac = client.get("provisional_mac").toString();
            var observations = rows("SELECT o.*, v.host_id, v.host_name FROM radio_observation o JOIN radio_visit v ON v.id = o.visit_id WHERE o.client_id = ?", source);
            if (observations.isEmpty() || observations.stream().anyMatch(o -> !visibleHosts().contains(o.get("host_id"))))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Sem acesso a todos os APs deste vínculo.");
            var target = one("SELECT id, login FROM radio_client WHERE login = ?", login.strip());
            var periods = rows("SELECT client_id, valid_from, valid_to, origin FROM radio_mac_binding WHERE mac = ? ORDER BY valid_from", mac);
            long count = rows("SELECT o.visit_id, COUNT(*) AS samples, MIN(o.measured_at) AS first_at, MAX(o.measured_at) AS last_at FROM radio_observation o JOIN radio_visit v ON v.id = o.visit_id WHERE o.client_id = ? AND o.measured_at >= ? AND v.closed = TRUE GROUP BY o.visit_id", source, from).stream()
                .filter(r -> number(r, "samples") >= minimum && number(r, "last_at") - number(r, "first_at") >= coverage).count();
            var preview = new LinkedHashMap<String, Object>();
            preview.put("target", target); preview.put("collections", count); preview.put("periods", periods); preview.put("from", from);
            if (!confirm) return preview;
            if (periods.stream().anyMatch(p -> !"PROVISIONAL".equals(p.get("origin")) && p.get("valid_to") != null && number(p, "valid_to") > from))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "O início cruza um vínculo anterior encerrado. Escolha uma data posterior.");
            // Keep older provisional periods separate when a manual interval starts later.
            String destination;
            boolean older = observations.stream().anyMatch(o -> number(o, "measured_at") < from);
            if (target != null) destination = target.get("id").toString();
            else if (!older) {
                destination = source;
                db.update("UPDATE radio_client SET login = ?, provisional_mac = NULL WHERE id = ?", login.strip(), source);
            } else {
                destination = id(); db.update("INSERT INTO radio_client(id, login, created_at) VALUES (?, ?, ?)", destination, login.strip(), System.currentTimeMillis());
            }
            db.update("UPDATE radio_mac_binding SET valid_to = valid_from WHERE mac = ? AND origin = 'PROVISIONAL' AND valid_to IS NULL AND valid_from > ?", mac, from);
            db.update("UPDATE radio_mac_binding SET valid_to = ? WHERE mac = ? AND valid_to IS NULL AND valid_from <= ?", from, mac, from);
            if (periods.stream().anyMatch(p -> !"PROVISIONAL".equals(p.get("origin")) && number(p, "valid_from") > from))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Há vínculo posterior ao início selecionado.");
            db.update("INSERT INTO radio_mac_binding(id, client_id, mac, valid_from, origin, operator_name, recorded_at) VALUES (?, ?, ?, ?, 'MANUAL', ?, ?)",
                id(), destination, mac, from, operator, System.currentTimeMillis());
            if (!destination.equals(source)) merge(source, destination, mac, from, Long.MAX_VALUE);
            preview.put("clientId", destination); return preview;
        });
    }
}
