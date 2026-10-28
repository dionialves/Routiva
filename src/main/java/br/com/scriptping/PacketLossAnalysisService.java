package br.com.scriptping;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Publishes complete database-derived snapshots; readers never trigger analysis or SQL. */
@Service
public class PacketLossAnalysisService {

    private static final org.slf4j.Logger CYCLE_LOG = org.slf4j.LoggerFactory.getLogger("routiva.indicators");
    private final String runId = UUID.randomUUID().toString();
    private long cycleSequence, lastPublishedCycle;

    private static final class Cycle {
        long fetched, incorporated, late, discarded, queued, released;
        long expired5m, expired1h, expired8h, removed, queryNanos, callbackNanos, availabilityNanos;
        String stage = "database_boundary", resetReason = "none";
        boolean initial;
    }

    private record Key(String hostId, String target) {}

    private final SampleStore store;
    private final PacketLossAnalyzer analyzer;
    private final PacketLossAnalysis empty;
    private record Snapshot(Map<Key, PacketLossAnalysis> indicators,
        Map<Key, Map<Integer, HostAvailabilityService.Report>> availability,
        Map<Integer, HostAvailabilityService.Report> emptyReports) {}
    private volatile Snapshot snapshot;
    private final long availabilityRetention;
    private final Map<Key, AvailabilityHistory> availabilityHistories = new HashMap<>();
    private final Map<Key, IncrementalPingHistory> histories = new HashMap<>();
    private final PriorityQueue<PingSample> future = new PriorityQueue<>(
        Comparator.comparingLong(PingSample::measuredAt).thenComparingLong(PingSample::id)
    );
    private long cursor, evaluatedAt = Long.MIN_VALUE;

    public PacketLossAnalysisService(SampleStore store, PacketLossPolicy rules, long interval, long timeout) {
        this(store, rules, interval, timeout, 48);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PacketLossAnalysisService(
        SampleStore store,
        PacketLossPolicy rules,
        @Value("${monitor.interval-ms}") long interval,
        @Value("${monitor.timeout-ms}") long timeout,
        @Value("${monitor.retention-hours:48}") long retentionHours
    ) {
        this.store = store;
        analyzer = new PacketLossAnalyzer(rules, interval, timeout);
        empty = analyzer.analyze(new RollingPingHistory(), 0);
        if (retentionHours < 1) throw new IllegalArgumentException("Retenção inválida");
        availabilityRetention = Math.min(48, retentionHours) * 3_600_000L;
        snapshot = new Snapshot(Map.of(), Map.of(), new AvailabilityHistory().reports(
            0, availabilityRetention, runId + ":0", false));
    }

    @Scheduled(fixedRate = 10000, scheduler = "indicatorScheduler")
    public void refresh() {
        refreshAt(System.currentTimeMillis());
    }

    // On failure discard unpublished working state and rebuild next cycle; readers retain the snapshot.
    synchronized void refreshAt(long now) {
        long cycle = ++cycleSequence, started = System.nanoTime();
        Cycle counts = new Cycle();
        counts.initial = evaluatedAt == Long.MIN_VALUE;
        try {
            if (now < evaluatedAt) {
                reset();
                counts.resetReason = "clock_moved_backwards";
            }
            long queryStarted = System.nanoTime();
            long boundary;
            try { boundary = store.committedBoundary(); }
            finally { counts.queryNanos += System.nanoTime() - queryStarted; }
            if (boundary < cursor) {
                reset();
                counts.resetReason = "database_boundary_rewound";
            }
            counts.initial = evaluatedAt == Long.MIN_VALUE;
            long previousReference = evaluatedAt;
            long historyCutoff = now - analyzer.lookbackMs();
            long cutoff = now - Math.max(analyzer.lookbackMs(), availabilityRetention);
            counts.stage = "expiration";
            histories.values().forEach(history -> {
                long five = history.metrics(300000L).all;
                long hour = history.metrics(3600000L).all;
                long eight = history.metrics(28800000L).all;
                long retained = history.size();
                analyzer.advance(history, now);
                counts.expired5m += five - history.metrics(300000L).all;
                counts.expired1h += hour - history.metrics(3600000L).all;
                counts.expired8h += eight - history.metrics(28800000L).all;
                history.expire(historyCutoff);
                counts.removed += retained - history.size();
            });
            histories.values().removeIf(IncrementalPingHistory::isEmpty);
            availabilityHistories.values().forEach(history -> history.expire(now - availabilityRetention));
            availabilityHistories.values().removeIf(AvailabilityHistory::isEmpty);
            counts.stage = "database_query";
            if (boundary > cursor) {
                queryStarted = System.nanoTime();
                try {
                    store.readIncrement(cursor, boundary, cutoff, sample -> {
                        long callbackStarted = System.nanoTime();
                        counts.stage = "sample_processing";
                        try {
                            counts.fetched++;
                            if (!IpAddress.isValid(sample.target()) || sample.measuredAt() < cutoff) {
                                counts.discarded++;
                            } else if (sample.measuredAt() > now) {
                                future.add(sample);
                                counts.queued++;
                            } else {
                                incorporate(sample, now);
                                counts.incorporated++;
                                if (!counts.initial && sample.measuredAt() < previousReference) counts.late++;
                            }
                            counts.stage = "database_query";
                        } finally { counts.callbackNanos += System.nanoTime() - callbackStarted; }
                    });
                } finally {
                    counts.queryNanos += System.nanoTime() - queryStarted - counts.callbackNanos;
                }
            }
            counts.stage = "pending_processing";
            while (!future.isEmpty() && future.peek().measuredAt() <= now) {
                var sample = future.remove();
                counts.released++;
                if (sample.measuredAt() >= cutoff) {
                    incorporate(sample, now);
                    counts.incorporated++;
                } else counts.discarded++;
            }
            counts.stage = "publication";
            Map<Key, PacketLossAnalysis> next = new HashMap<>();
            histories.forEach((key, history) -> next.put(key, analyzer.analyze(history, now)));
            counts.stage = "availability_publication";
            long availabilityStarted = System.nanoTime();
            Map<Key, Map<Integer, HostAvailabilityService.Report>> reports = new HashMap<>();
            String version = runId + ":" + cycle;
            availabilityHistories.forEach((key, history) -> reports.put(key,
                history.reports(now, availabilityRetention, version, true)));
            var emptyReports = new AvailabilityHistory().reports(now, availabilityRetention, version, true);
            counts.availabilityNanos = System.nanoTime() - availabilityStarted;
            snapshot = new Snapshot(Map.copyOf(next), Map.copyOf(reports), emptyReports);
            cursor = boundary;
            evaluatedAt = now;
            lastPublishedCycle = cycle;
        } catch (RuntimeException failure) {
            reset();
            CYCLE_LOG.error(
                "event=CYCLE_FAILED run_id={} cycle={} reference_at={} mode={} stage={} error={} fetched={} incorporated={} discarded={} total_ms={} indicators_published=false last_published_cycle={} next_cycle=initial_load",
                runId, cycle, java.time.Instant.ofEpochMilli(now), counts.initial ? "initial_load" : "incremental",
                counts.stage, failure.getClass().getSimpleName(), counts.fetched, counts.incorporated,
                counts.discarded, elapsedMillis(started), lastPublishedCycle
            );
            throw failure;
        }
        long duration = System.nanoTime() - started;
        CYCLE_LOG.info(
            "event={} run_id={} cycle={} reference_at={} hosts={} fetched={} incorporated={} late_arrivals={} future_queued={} future_released={} pending_future={} discarded={} expired_5m={} expired_1h={} expired_8h={} removed_pings={} retained_pings={} query_ms={} processing_ms={} total_ms={} schedule_overrun={} cursor={} reset_reason={} availability_hosts={} availability_buckets={} availability_ms={} indicators_published=true",
            counts.initial ? "INITIAL_LOAD_COMPLETED" : "CYCLE_COMPLETED", runId, cycle,
            java.time.Instant.ofEpochMilli(now), histories.size(), counts.fetched, counts.incorporated,
            counts.late, counts.queued, counts.released, future.size(), counts.discarded,
            counts.expired5m, counts.expired1h, counts.expired8h, counts.removed,
            histories.values().stream().mapToLong(IncrementalPingHistory::size).sum(),
            counts.queryNanos / 1000000, Math.max(0, duration - counts.queryNanos) / 1000000,
            duration / 1000000, duration > 10000000000L, cursor, counts.resetReason,
            availabilityHistories.size(), availabilityHistories.values().stream().mapToLong(AvailabilityHistory::bucketCount).sum(),
            counts.availabilityNanos / 1000000
        );
    }

    private static long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1000000;
    }

    private void incorporate(PingSample sample, long now) {
        if (sample.measuredAt() >= now - availabilityRetention) {
            availabilityHistories.computeIfAbsent(new Key(sample.hostId(), sample.target()),
                key -> new AvailabilityHistory()).add(sample);
        }
        if (sample.measuredAt() <= now - analyzer.lookbackMs()) return;
        histories.computeIfAbsent(new Key(sample.hostId(), sample.target()), key -> {
            var history = analyzer.newIncrementalHistory();
            analyzer.advance(history, now);
            return history;
        }).add(sample);
    }

    private void reset() {
        histories.clear();
        availabilityHistories.clear();
        future.clear();
        cursor = 0;
        evaluatedAt = Long.MIN_VALUE;
    }

    public HostAvailabilityService.Report availability(Host host, int hours) {
        var published = snapshot;
        return published.availability().getOrDefault(new Key(host.id(), host.ip()), published.emptyReports()).get(hours);
    }

    public Map<String, PacketLossAnalysis> current(List<Host> hosts) {
        var available = snapshot.indicators();
        Map<String, PacketLossAnalysis> result = new HashMap<>();
        for (Host host : hosts) {
            result.put(host.id(), available.getOrDefault(new Key(host.id(), host.ip()), empty));
        }
        return result;
    }
}
