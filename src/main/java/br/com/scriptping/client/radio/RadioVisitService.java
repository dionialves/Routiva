package br.com.scriptping.client.radio;

import br.com.scriptping.equipment.EquipmentProvider;
import br.com.scriptping.ixc.IxcClient;
import br.com.scriptping.zabbix.ZabbixService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** The live request path only touches memory. Closed visits are processed by a dedicated worker. */
@Service
public class RadioVisitService {
    public record Sample(long at, String mac, Map<String, String> raw, List<String> observedLogins) {}
    public record Latency(String mac, long at, Map<String, Object> data) {}
    public record Batch(String id, String host, String hostName, String owner, long startedAt,
                        long touchedAt, List<Sample> samples, List<Latency> tests) {}
    private static final class Visit {
        final String id = UUID.randomUUID().toString(), host, hostName, owner;
        final long startedAt;
        final Map<String, NavigableMap<Long, Sample>> samples = new LinkedHashMap<>();
        final Map<String, List<String>> identities = new HashMap<>();
        final Map<String, String> ips = new HashMap<>();
        final Map<String, Latency> tests = new LinkedHashMap<>();
        long touchedAt, closedAt, revision, persistedRevision = -1, retryAt;
        boolean closed, queued;
        Visit(String host, String hostName, String owner, long now) {
            this.host = host; this.hostName = hostName; this.owner = owner; startedAt = now; touchedAt = now;
        }
        Batch batch() {
            return new Batch(id, host, hostName, owner, startedAt, touchedAt,
                samples.values().stream().flatMap(m -> m.values().stream()).sorted(Comparator.comparingLong(Sample::at)).toList(),
                List.copyOf(tests.values()));
        }
    }
    private final ConcurrentMap<String, Visit> visits = new ConcurrentHashMap<>();
    private final RadioClientService persistence;
    private final ZabbixService inventory;
    private final long inactivity;
    private final Clock clock;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        var thread = new Thread(r, "radio-history-persistence"); thread.setDaemon(true); return thread;
    });
    @org.springframework.beans.factory.annotation.Autowired
    public RadioVisitService(RadioClientService persistence, ZabbixService inventory,
            @Value("${radio.history.inactivity-ms:120000}") long inactivity) {
        this(persistence, inventory, inactivity, Clock.systemUTC());
    }
    RadioVisitService(RadioClientService persistence, ZabbixService inventory, long inactivity, Clock clock) {
        this.persistence = persistence; this.inventory = inventory; this.inactivity = Math.max(30000, inactivity); this.clock = clock;
    }
    @PostConstruct public void startWorker() {
        worker.execute(persistence::recoverUnfinishedVisits);
        worker.scheduleWithFixedDelay(this::retry, 1, 1, TimeUnit.SECONDS);
    }
    public String open(String host, String owner) {
        String name = inventory.hosts().stream().filter(h -> h.id().equals(host)).map(h -> h.name()).findFirst().orElse(host);
        var visit = new Visit(host, name, owner, clock.millis()); visits.put(visit.id, visit); return visit.id;
    }
    private Visit visit(String id, String host, String owner) {
        var visit = visits.get(id);
        if (visit == null || !visit.host.equals(host) || !visit.owner.equals(owner))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Visita não encontrada.");
        return visit;
    }
    public void owned(String id, String host, String owner) { visit(id, host, owner); }
    public boolean active(String id) {
        var visit = visits.get(id); if (visit == null) return false;
        synchronized (visit) { return !visit.closed; }
    }
    public void observe(String id, String host, String owner, EquipmentProvider.Snapshot snapshot) {
        var visit = visit(id, host, owner);
        if (snapshot.warning() != null && !snapshot.warning().isBlank()) return;
        synchronized (visit) {
            if (visit.closed || snapshot.measuredAt() < visit.startedAt || snapshot.measuredAt() > clock.millis() + 1000) return;
            for (var raw : snapshot.rows()) {
                if ("disconnected".equals(raw.get("connection-status"))) continue;
                String mac;
                try { mac = IxcClient.normalizeMac(raw.get("mac-address")); } catch (ResponseStatusException invalid) { continue; }
                var samples = visit.samples.computeIfAbsent(mac, key -> new TreeMap<>());
                // A stale or repeated response must never count as another equipment reading.
                if (!samples.isEmpty() && samples.lastKey() >= snapshot.measuredAt()) continue;
                var source = new LinkedHashMap<>(raw);
                if (!source.containsKey("ip-address") && visit.ips.containsKey(mac)) source.put("ip-address", visit.ips.get(mac));
                samples.put(snapshot.measuredAt(), new Sample(snapshot.measuredAt(), mac,
                    Collections.unmodifiableMap(source), visit.identities.getOrDefault(mac, List.of())));
            }
            visit.touchedAt = clock.millis();
        }
    }
    /** Reuses names already requested by the AP UI; performs no IXC request of its own. */
    public void identities(String id, String host, String owner, Map<String, List<String>> names) {
        identities(id, host, owner, names, Map.of());
    }
    public void identities(String id, String host, String owner, Map<String, List<String>> names, Map<String, String> ips) {
        var visit = visit(id, host, owner);
        synchronized (visit) {
            if (visit.closed) return;
            names.keySet().forEach(visit.ips::remove);
            ips.forEach((mac, ip) -> { if (br.com.scriptping.IpAddress.isValid(ip)) visit.ips.put(IxcClient.normalizeMac(mac), ip); });
            names.forEach((mac, logins) -> visit.identities.put(IxcClient.normalizeMac(mac),
                logins.stream().filter(Objects::nonNull).map(String::strip).filter(s -> !s.isBlank()).distinct().toList()));
        }
    }
    public void latency(String id, String host, String owner, String mac, long at, Map<String, Object> stats) {
        if (id == null || mac == null || mac.isBlank()) return;
        var visit = visit(id, host, owner);
        synchronized (visit) {
            var samples = visit.samples.get(IxcClient.normalizeMac(mac));
            long start = stats.get("startedAt") instanceof Number n ? n.longValue() : at;
            var observation = samples == null ? null : samples.floorEntry(start);
            if (observation == null) return;
            var data = new LinkedHashMap<>(stats); data.put("observationAt", observation.getKey());
            String key = mac + "/" + at;
            if (visit.tests.containsKey(key)) return;
            visit.tests.put(key, new Latency(mac, at, Collections.unmodifiableMap(data))); visit.revision++;
            // A test may complete just after pagehide. Update that same closed visit asynchronously.
            if (visit.closed) enqueue(visit);
        }
    }
    public void close(String id, String host, String owner) { finish(visit(id, host, owner)); }
    private void finish(Visit visit) {
        synchronized (visit) {
            if (!visit.closed) { visit.closed = true; visit.closedAt = clock.millis(); visit.revision++; }
            enqueue(visit);
        }
    }
    private void enqueue(Visit visit) {
        if (visit.queued || visit.persistedRevision == visit.revision || visit.retryAt > clock.millis()) return;
        visit.queued = true;
        worker.execute(() -> persist(visit));
    }
    private void persist(Visit visit) {
        Batch batch; long revision;
        synchronized (visit) { batch = visit.batch(); revision = visit.revision; }
        try {
            persistence.persistVisit(batch);
            synchronized (visit) { visit.persistedRevision = revision; visit.retryAt = 0; }
        } catch (RuntimeException failure) {
            synchronized (visit) { visit.retryAt = clock.millis() + 30000; }
            LoggerFactory.getLogger(getClass()).warn("Persistência da visita rádio {} falhou; nova tentativa em 30 segundos", visit.id, failure);
        } finally {
            synchronized (visit) { visit.queued = false; }
        }
    }
    private void retry() {
        for (var visit : visits.values()) synchronized (visit) { if (visit.closed) enqueue(visit); }
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 15000)
    public void expire() {
        long now = clock.millis();
        for (var visit : visits.values()) synchronized (visit) {
            if (!visit.closed && visit.touchedAt < now - inactivity) finish(visit);
            if (visit.closed && !visit.queued && visit.persistedRevision == visit.revision && now - visit.closedAt > 600000)
                visits.remove(visit.id, visit);
        }
    }
    @PreDestroy public void shutdown() {
        visits.values().forEach(this::finish);
        worker.shutdown();
        try { if (!worker.awaitTermination(15, TimeUnit.SECONDS)) worker.shutdownNow(); }
        catch (InterruptedException interrupted) { worker.shutdownNow(); Thread.currentThread().interrupt(); }
    }
}
