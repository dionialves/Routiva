package br.com.scriptping.maps;

import br.com.scriptping.zabbix.ZabbixClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Demand-driven, shared interface cache. Registry locks never cover Zabbix I/O. */
@Service
public class MapZabbixReadings implements AutoCloseable {
    public record Key(String hostId, String name) {}
    private record Binding(JsonNode status, JsonNode rx, JsonNode tx, JsonNode speed, String error) {}
    private record Catalogue(List<JsonNode> items, long at) {}
    private static final long INTERVAL = 60_000, IDLE = 300_000, DISCOVERY = 600_000;
    private static final Pattern METRIC = Pattern.compile("^net\\.if\\.(status|operstatus|in|out|speed|type)\\[(.*)]$");
    private final ZabbixClient client;
    private final LongSupplier clock;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("map-zabbix-", 0).factory());
    private final Map<Key, Long> interests = new HashMap<>();
    private final Map<Key, MappedConnectionController.Endpoint> cache = new HashMap<>();
    private record Discovery(List<Map<String, String>> rows, long at) {}
    private final Map<String, Catalogue> catalogues = new ConcurrentHashMap<>();
    private final Map<String, Discovery> discoveries = new ConcurrentHashMap<>();
    private CompletableFuture<Void> pending;
    private long nextRefresh;
    private boolean closed;

    @org.springframework.beans.factory.annotation.Autowired
    public MapZabbixReadings(ZabbixClient client) { this(client, System::currentTimeMillis); }
    MapZabbixReadings(ZabbixClient client, LongSupplier clock) { this.client = client; this.clock = clock; }

    public Map<Key, MappedConnectionController.Endpoint> read(Set<Key> keys, boolean force) {
        if (keys.isEmpty()) return Map.of();
        synchronized (this) { keys.forEach(key -> interests.put(key, clock.getAsLong())); }
        // First load and manual refresh join a single flight; ordinary polling is memory-only.
        CompletableFuture<Void> flight;
        synchronized (this) {
            flight = (force || !cache.keySet().containsAll(keys)) ? start() : null;
        }
        if (flight != null) flight.join();
        synchronized (this) {
            // New interfaces may have arrived after the shared flight took its snapshot.
            if (cache.keySet().containsAll(keys)) return snapshot(keys);
            flight = start();
        }
        flight.join();
        synchronized (this) { return snapshot(keys); }
    }
    private Map<Key, MappedConnectionController.Endpoint> snapshot(Set<Key> keys) {
        var result = new HashMap<Key, MappedConnectionController.Endpoint>();
        keys.forEach(key -> result.put(key, cache.getOrDefault(key, unavailable(key, "Aguardando coleta", clock.getAsLong()))));
        return Map.copyOf(result);
    }
    private CompletableFuture<Void> start() {
        if (pending != null) return pending;
        if (closed) throw new IllegalStateException("Map cache closed");
        var keys = Set.copyOf(interests.keySet());
        pending = CompletableFuture.runAsync(() -> collect(keys), worker);
        return pending;
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 1000)
    public synchronized void tick() {
        long now = clock.getAsLong();
        interests.entrySet().removeIf(entry -> now - entry.getValue() >= IDLE);
        cache.keySet().retainAll(interests.keySet());
        if (!interests.isEmpty() && now >= nextRefresh) start();
        var activeHosts = new HashSet<String>();
        interests.keySet().forEach(key -> activeHosts.add(key.hostId()));
        catalogues.entrySet().removeIf(entry -> !activeHosts.contains(entry.getKey()) && now - entry.getValue().at() >= IDLE);
        discoveries.entrySet().removeIf(entry -> now - entry.getValue().at() >= IDLE);
    }
    private void collect(Set<Key> keys) {
        long now = clock.getAsLong();
        var result = new HashMap<Key, MappedConnectionController.Endpoint>();
        try {
            var hosts = new HashSet<String>();
            keys.forEach(key -> hosts.add(key.hostId()));
            var missing = new HashSet<String>();
            hosts.forEach(host -> { if (!catalogues.containsKey(host) || now - catalogues.get(host).at() >= DISCOVERY) missing.add(host); });
            if (!missing.isEmpty()) {
                var discovered = client.mapInterfaceItems(missing);
                for (String host : missing) catalogues.put(host, new Catalogue(discovered.stream()
                    .filter(item -> host.equals(item.path("hostid").asText())).toList(), now));
            }
            var bindings = new HashMap<Key, Binding>();
            var ids = new HashSet<String>();
            for (var key : keys) {
                var binding = bind(key, catalogues.get(key.hostId()).items());
                bindings.put(key, binding);
                for (var item : Arrays.asList(binding.status(), binding.rx(), binding.tx(), binding.speed()))
                    if (item != null) ids.add(item.path("itemid").asText());
            }
            var values = new HashMap<String, JsonNode>();
            client.mapItemValues(ids).forEach(item -> values.put(item.path("itemid").asText(), item));
            for (var key : keys) result.put(key, endpoint(key, bindings.get(key), values, clock.getAsLong()));
        } catch (RuntimeException ex) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Map Zabbix refresh failed: {}", ex.getClass().getSimpleName());
            synchronized (this) {
                for (var key : keys) {
                    var old = cache.get(key);
                    result.put(key, old == null ? new MappedConnectionController.Endpoint(0, key.name(), false, null, null,
                        "Consulta ao Zabbix indisponível", null, 0, 0, 0, null, now, "Consulta ao Zabbix indisponível")
                        : new MappedConnectionController.Endpoint(old.measuredAt(), old.name(), old.up(), old.rxBps(), old.txBps(),
                            old.error(), old.capacityBps(), old.validUntil(), old.rxValidUntil(), old.txValidUntil(),
                            old.trafficError(), old.queriedAt(), "Consulta ao Zabbix indisponível"));
                }
            }
        } finally {
            synchronized (this) {
                result.forEach((key, value) -> { if (interests.containsKey(key)) cache.put(key, value); });
                nextRefresh = clock.getAsLong() + INTERVAL;
                pending = null;
            }
        }
    }
    /** One-shot editing discovery, serialized with collection and cached for one minute. */
    public List<Map<String, String>> discover(String hostId) {
        return discover(hostId, false);
    }
    public List<Map<String, String>> discover(String hostId, boolean includeLogical) {
        try {
            return worker.submit(() -> discoverRows(hostId).stream()
                .filter(row -> includeLogical || !Set.of("24", "53", "131", "135", "161").contains(row.get("snmp-type")))
                .toList()).get();
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                "Consulta de interfaces interrompida.");
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof org.springframework.web.server.ResponseStatusException response) throw response;
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_GATEWAY,
                "Não foi possível carregar as interfaces do Zabbix. Confira a integração e os itens net.if.* do host.");
        }
    }
    private List<Map<String, String>> discoverRows(String hostId) {
        long now = clock.getAsLong();
        var cached = discoveries.get(hostId);
        if (cached != null && now - cached.at() < INTERVAL) return cached.rows();
        var catalogue = catalogues.get(hostId);
        if (catalogue == null || now - catalogue.at() >= INTERVAL) {
            catalogue = new Catalogue(client.mapInterfaceItems(Set.of(hostId)).stream()
                .filter(item -> hostId.equals(item.path("hostid").asText())).toList(), now);
            catalogues.put(hostId, catalogue);
        }
        var items = catalogue.items();
        var ids = new HashSet<String>();
        for (var item : items) {
            var match = METRIC.matcher(item.path("key_").asText());
            if (match.matches() && Set.of("status", "operstatus", "speed", "type").contains(match.group(1)))
                ids.add(item.path("itemid").asText());
        }
        var values = new HashMap<String, JsonNode>();
        client.mapItemValues(ids).forEach(item -> values.put(item.path("itemid").asText(), item));
        var rows = new ArrayList<Map<String, String>>();
        var names = new HashSet<String>();
        for (var item : items) {
            if (!item.path("key_").asText().matches("net\\.if\\.(?:status|operstatus)\\[.*")) continue;
            String name = interfaceName(item);
            if (name.isBlank()) continue;
            var related = items.stream().filter(other -> identity(item).equals(identity(other))).toList();
            var typeItem = unique(related, "type");
            var typeValue = value(typeItem, values);
            String snmpType = issue(typeItem, typeValue) == null ? typeValue.path("lastvalue").asText() : "";
            if (!names.add(normalized(name))) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_GATEWAY, "O Zabbix contém interfaces com nomes duplicados. Revise os itens e as tags do host.");
            var row = new LinkedHashMap<String, String>();
            row.put("name", name); row.put("source", "zabbix");
            row.put("snmp-type", snmpType);
            row.put("type", Set.of("71", "157").contains(snmpType) ? "wireless" : "ether");
            for (String tag : List.of("connector", "ifname", "ifalias")) {
                String tagValue = tag(item, tag);
                if (!tagValue.isBlank()) row.put(tag.equals("ifalias") ? "comment" : tag.equals("ifname") ? "default-name" : tag, tagValue);
            }
            var status = value(item, values);
            if (issue(item, status) == null && validUntil(item, status) >= now && timestamp(status) <= now) {
                String state = status.path("lastvalue").asText();
                if (Set.of("1", "2", "6", "7").contains(state)) row.put("running", Boolean.toString("1".equals(state)));
            }
            var speedItem = unique(related, "speed");
            var speedValue = value(speedItem, values);
            if (issue(speedItem, speedValue) == null && validUntil(speedItem, speedValue) >= now && timestamp(speedValue) <= now
                && "bps".equals(speedItem.path("units").asText())) row.put("speed", speedValue.path("lastvalue").asText() + "bps");
            rows.add(Map.copyOf(row));
        }
        rows.sort((a, b) -> compareInterfaceNames(a.get("name"), b.get("name")));
        var result = List.copyOf(rows);
        discoveries.put(hostId, new Discovery(result, now));
        return result;
    }
    static int compareInterfaceNames(String a, String b) {
        var parts = Pattern.compile("[0-9]+|[^0-9]+");
        var left = parts.matcher(a);
        var right = parts.matcher(b);
        while (left.find()) {
            if (!right.find()) return 1;
            String x = left.group(), y = right.group();
            int order = Character.isDigit(x.charAt(0)) && Character.isDigit(y.charAt(0))
                ? new java.math.BigInteger(x).compareTo(new java.math.BigInteger(y))
                : String.CASE_INSENSITIVE_ORDER.compare(x, y);
            if (order != 0) return order;
        }
        return right.find() ? -1 : 0;
    }
    private static String tag(JsonNode item, String name) {
        for (var tag : item.path("tags")) if (name.equalsIgnoreCase(tag.path("tag").asText())) return tag.path("value").asText().strip();
        return "";
    }
    private static String interfaceName(JsonNode item) {
        for (String field : List.of("ifname", "interface", "ifdescr")) {
            String value = tag(item, field);
            if (!value.isBlank()) return value;
        }
        var name = Pattern.compile("(?i)^Interface (.+?): (?:.*)$").matcher(item.path("name").asText());
        return name.matches() ? name.group(1).replaceFirst("\\s*\\([^)]*\\)$", "").strip() : identity(item);
    }
    private static String normalized(String name) {
        return name.strip().toLowerCase(Locale.ROOT).replaceFirst(" (?=[0-9]+/)", "-");
    }
    private static String identity(JsonNode item) {
        var match = METRIC.matcher(item.path("key_").asText());
        if (!match.matches()) return "";
        String parameter = match.group(2).split(",", 2)[0].replace("\"", "");
        return parameter.replaceFirst("^(?:ifOperStatus|ifHCInOctets|ifHCOutOctets|ifInOctets|ifOutOctets|ifHighSpeed|ifSpeed|ifType)[.]", "");
    }
    private static boolean matches(Key key, JsonNode item) {
        String target = normalized(key.name());
        if (target.equals(normalized(identity(item)))) return true;
        for (var tag : item.path("tags")) {
            if (Set.of("interface", "ifname", "ifdescr").contains(tag.path("tag").asText().toLowerCase(Locale.ROOT))
                && target.equals(normalized(tag.path("value").asText()))) return true;
        }
        // Standard SNMP templates: Interface <ifName>(<ifAlias>): <metric>.
        var name = Pattern.compile("(?i)^Interface (.+?): (?:.*)$").matcher(item.path("name").asText());
        if (!name.matches()) return false;
        String label = name.group(1);
        return target.equals(normalized(label)) || target.equals(normalized(label.replaceFirst("\\s*\\([^)]*\\)$", "")));
    }
    private static Binding bind(Key key, List<JsonNode> items) {
        var status = items.stream().filter(item -> item.path("key_").asText().matches("net\\.if\\.(?:status|operstatus)\\[.*"))
            .filter(item -> matches(key, item)).toList();
        if (status.size() != 1) return new Binding(null, null, null, null,
            status.isEmpty() ? "Interface sem correspondência no Zabbix" : "Correspondência ambígua no Zabbix");
        String identity = identity(status.getFirst());
        var related = items.stream().filter(item -> identity.equals(identity(item))).toList();
        return new Binding(status.getFirst(), unique(related, "in"), unique(related, "out"), unique(related, "speed"), null);
    }
    private static JsonNode unique(List<JsonNode> items, String metric) {
        var found = items.stream().filter(item -> item.path("key_").asText().startsWith("net.if." + metric + "[")).toList();
        return found.size() == 1 ? found.getFirst() : null;
    }
    private static JsonNode value(JsonNode item, Map<String, JsonNode> values) {
        return item == null ? null : values.get(item.path("itemid").asText());
    }
    private static String issue(JsonNode item, JsonNode value) {
        if (item == null) return "Item não encontrado ou ambíguo no Zabbix";
        if (value == null) return "Item indisponível no Zabbix";
        if (value.path("status").asInt() != 0) return "Item desabilitado no Zabbix";
        for (var host : value.path("hosts")) if (host.path("status").asInt() != 0) return "Host desabilitado no Zabbix";
        if (value.path("state").asInt() != 0) return "Item não suportado no Zabbix";
        if (value.path("lastclock").asLong() <= 0) return "Aguardando amostra do Zabbix";
        return null;
    }
    private static long timestamp(JsonNode value) { return value == null ? 0 : value.path("lastclock").asLong() * 1000; }
    private static long validUntil(JsonNode item, JsonNode value) {
        if (item == null || timestamp(value) == 0) return 0;
        long interval = 60;
        var delay = Pattern.compile("^(\\d+)([smhd]?)(?:;.*)?$").matcher(item.path("delay").asText());
        if (delay.matches()) {
            try {
                interval = Long.parseLong(delay.group(1)) * switch (delay.group(2)) { case "m" -> 60; case "h" -> 3600; case "d" -> 86400; default -> 1; };
            } catch (NumberFormatException ignored) { }
        }
        return timestamp(value) + Math.max(180_000, Math.min(86_400, interval) * 3000);
    }
    private static String rate(JsonNode item, JsonNode value) {
        if (issue(item, value) != null) return null;
        String units = item.path("units").asText();
        long multiplier = switch (units) { case "bps", "b/s", "bit/s" -> 1; case "Bps", "B/s", "byte/s" -> 8; default -> 0; };
        if (multiplier == 0) return null; // Never present cumulative octets as an instantaneous rate.
        try {
            var rate = new BigDecimal(value.path("lastvalue").asText()).multiply(BigDecimal.valueOf(multiplier));
            return rate.signum() >= 0 ? rate.toPlainString() : null;
        } catch (NumberFormatException ex) { return null; }
    }
    private static MappedConnectionController.Endpoint endpoint(Key key, Binding binding, Map<String, JsonNode> values, long now) {
        if (binding.error() != null) return unavailable(key, binding.error(), now);
        var status = value(binding.status(), values);
        var rx = value(binding.rx(), values); var tx = value(binding.tx(), values); var speed = value(binding.speed(), values);
        String error = issue(binding.status(), status);
        String state = status == null ? "" : status.path("lastvalue").asText();
        if (error == null && !Set.of("1", "2", "6", "7").contains(state)) error = "Estado operacional desconhecido no Zabbix";
        String rxRate = timestamp(rx) > now ? null : rate(binding.rx(), rx);
        String txRate = timestamp(tx) > now ? null : rate(binding.tx(), tx);
        Long capacity = null;
        if (issue(binding.speed(), speed) == null && validUntil(binding.speed(), speed) >= now && timestamp(speed) <= now) {
            try {
                String units = binding.speed().path("units").asText();
                long factor = switch (units) { case "bps", "b/s" -> 1; case "Mbps" -> 1_000_000; case "Gbps" -> 1_000_000_000; default -> 0; };
                var number = new BigDecimal(speed.path("lastvalue").asText()).multiply(BigDecimal.valueOf(factor)).longValueExact();
                if (number > 0) capacity = number;
            } catch (ArithmeticException | NumberFormatException ignored) { }
        }
        var trafficIssues = new ArrayList<String>();
        if (rxRate == null) trafficIssues.add("Recepção: " + Objects.requireNonNullElse(issue(binding.rx(), rx), "taxa ou unidade inválida no Zabbix"));
        if (txRate == null) trafficIssues.add("Transmissão: " + Objects.requireNonNullElse(issue(binding.tx(), tx), "taxa ou unidade inválida no Zabbix"));
        String trafficError = trafficIssues.isEmpty() ? null : String.join("; ", trafficIssues);
        return new MappedConnectionController.Endpoint(timestamp(status), key.name(), "1".equals(state), rxRate, txRate, error, capacity,
            validUntil(binding.status(), status), validUntil(binding.rx(), rx), validUntil(binding.tx(), tx), trafficError, now, null);
    }
    private static MappedConnectionController.Endpoint unavailable(Key key, String reason, long now) {
        return new MappedConnectionController.Endpoint(0, key.name(), false, null, null, reason, null, 0, 0, 0, null, now, null);
    }
    @Override @jakarta.annotation.PreDestroy
    public synchronized void close() { closed = true; worker.shutdownNow(); interests.clear(); cache.clear(); }
}
