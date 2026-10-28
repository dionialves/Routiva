package br.com.scriptping;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Publishes immutable snapshots with explicit coverage; readers never trigger analysis or SQL. */
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
        Map<Integer, HostAvailabilityService.Report> emptyReports, IndicatorLoadStatus load) {}
    private volatile Snapshot snapshot;
    private final long availabilityRetention;
    private final Map<Key, AvailabilityHistory> availabilityHistories = new HashMap<>();
    private final Map<Key, IncrementalPingHistory> histories = new HashMap<>();
    private final PriorityQueue<PingSample> future = new PriorityQueue<>(
        Comparator.comparingLong(PingSample::measuredAt).thenComparingLong(PingSample::id)
    );
    private long cursor, evaluatedAt = Long.MIN_VALUE;
    private AnalyticsCheckpointStore checkpoints;
    private String compatibility, sourceEpoch;
    private long checkpointInterval = 300000, lastCheckpointAttempt = Long.MIN_VALUE;
    private boolean recoveryAttempted;
    private IndicatorBootstrap bootstrap;
    private int pageSize = 10000;
    private long confirmedBoundary;

    @org.springframework.beans.factory.annotation.Autowired
    void configureBootstrap(@Value("${indicators.bootstrap.page-size:10000}") int pageSize,
        @Value("${indicators.bootstrap.delay-ms:1000}") long delayMs) {
        if (delayMs < 1) throw new IllegalArgumentException("Bootstrap delay must be positive");
        if (pageSize < 1 || pageSize > 100000) throw new IllegalArgumentException("Bootstrap page size must be between 1 and 100000");
        this.pageSize = pageSize;
    }

    // The same single-thread scheduler serializes live cycles and backfill work. Each turn
    // handles new IDs first, then at most one historical page, and yields before the next turn.
    @Scheduled(fixedDelayString = "${indicators.bootstrap.delay-ms:1000}", scheduler = "indicatorScheduler")
    public synchronized void continueBootstrap() {
        if (bootstrap != null || cursor < confirmedBoundary) refreshAt(System.currentTimeMillis());
    }

    private final java.util.concurrent.ExecutorService checkpointWriter = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "checkpoint-writer"); t.setDaemon(true); return t;
    });
    private volatile java.util.concurrent.Future<?> checkpointWrite;

    @org.springframework.beans.factory.annotation.Autowired
    void configureCheckpoints(AnalyticsCheckpointStore checkpoints,
        @Value("${indicators.checkpoint.interval-ms:300000}") long interval) {
        if (interval < 1000) throw new IllegalArgumentException("Checkpoint interval must be at least 1000ms");
        this.checkpoints = checkpoints; checkpointInterval = interval;
    }

    @jakarta.annotation.PreDestroy
    void closeCheckpointWriter() { checkpointWriter.shutdown(); }


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
        compatibility = CheckpointCodec.hash(("v1|" + interval + "|" + timeout + "|" + availabilityRetention
            + "|" + rules.getMinuteWindowSeconds() + "|" + rules.getHourWindowSeconds()
            + "|" + rules.getHourNormalMaxPercent() + "|" + rules.getHourCriticalMinPercent()
            + "|" + rules.getMinimumCoverageRatio() + "|" + rules.getMinimumSamples()
            + "|" + rules.getMaxGapMultiplier()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        snapshot = new Snapshot(Map.of(), Map.of(), new AvailabilityHistory().reports(
            0, availabilityRetention, runId + ":0", false),
            new IndicatorLoadStatus("STARTING", null, null, 0, 0, 0));
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
            if (!recoveryAttempted && checkpoints != null) restoreCheckpoint(now, boundary);
            recoveryAttempted = true;
            if (boundary < cursor) {
                reset();
                counts.resetReason = "database_boundary_rewound";
            }
            confirmedBoundary = boundary;
            counts.initial = evaluatedAt == Long.MIN_VALUE;
            if (counts.initial) {
                bootstrap = new IndicatorBootstrap(boundary);
                cursor = boundary; // IDs through boundary are owned by the historical reader.
            }
            boolean wasLoading = bootstrap != null;
            if (counts.initial) CYCLE_LOG.info("event=INITIAL_LOAD_STARTED run_id={} cycle={} boundary={}", runId, cycle, boundary);
            long[] progressAt = {System.nanoTime()};
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
            long liveThrough = cursor + Math.min((long) pageSize, boundary - cursor);
            if (liveThrough > cursor) {
                queryStarted = System.nanoTime();
                try {
                    store.readIncrement(cursor, liveThrough, cutoff, sample -> {
                        long callbackStarted = System.nanoTime();
                        counts.stage = "sample_processing";
                        try {
                            counts.fetched++;
                            if (System.nanoTime() - progressAt[0] >= 10_000_000_000L) {
                                CYCLE_LOG.info("event=LOAD_PROGRESS run_id={} cycle={} fetched={} sample_id={} boundary={} elapsed_ms={}",
                                    runId, cycle, counts.fetched, sample.id(), boundary, elapsedMillis(started));
                                progressAt[0] = System.nanoTime();
                            }
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
            cursor = liveThrough;
            if (bootstrap != null) {
                long historicalStarted = System.nanoTime();
                long callbacksBefore = counts.callbackNanos;
                counts.stage = "historical_query";
                try {
                    if (bootstrap.step(store, cutoff, pageSize, sample -> {
                        long callbackStarted = System.nanoTime();
                        counts.stage = "historical_processing";
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
                            }
                        } finally { counts.callbackNanos += System.nanoTime() - callbackStarted; }
                    })) bootstrap = null;
                } finally {
                    counts.queryNanos += System.nanoTime() - historicalStarted - (counts.callbackNanos - callbacksBefore);
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
            counts.availabilityNanos = publish(now, runId + ":" + cycle);
            if (wasLoading && bootstrap == null) CYCLE_LOG.info(
                "event=HISTORY_LOAD_COMPLETED run_id={} cycle={} cursor={} boundary={}", runId, cycle, cursor, boundary);
            evaluatedAt = now;
            lastPublishedCycle = cycle;
        } catch (RuntimeException failure) {
            reset();
            recoveryAttempted = false;
            CYCLE_LOG.error(
                "event=CYCLE_FAILED run_id={} cycle={} reference_at={} mode={} stage={} error={} fetched={} incorporated={} discarded={} total_ms={} indicators_published=false last_published_cycle={} next_cycle=recovery",
                runId, cycle, java.time.Instant.ofEpochMilli(now), counts.initial ? "initial_load" : "incremental",
                counts.stage, failure.getClass().getSimpleName(), counts.fetched, counts.incorporated,
                counts.discarded, elapsedMillis(started), lastPublishedCycle
            );
            throw failure;
        }
        scheduleCheckpoint(now);
        long duration = System.nanoTime() - started;
        CYCLE_LOG.info(
            "event={} run_id={} cycle={} reference_at={} hosts={} fetched={} incorporated={} late_arrivals={} future_queued={} future_released={} pending_future={} discarded={} expired_5m={} expired_1h={} expired_8h={} removed_pings={} retained_pings={} query_ms={} processing_ms={} total_ms={} schedule_overrun={} cursor={} reset_reason={} availability_hosts={} availability_buckets={} availability_ms={} indicators_published=true load_phase={} loaded_from={} target_from={} boundary={}",
            bootstrap != null ? "INITIAL_LOAD_PROGRESS" : counts.initial ? "INITIAL_LOAD_COMPLETED" : "CYCLE_COMPLETED", runId, cycle,
            java.time.Instant.ofEpochMilli(now), histories.size(), counts.fetched, counts.incorporated,
            counts.late, counts.queued, counts.released, future.size(), counts.discarded,
            counts.expired5m, counts.expired1h, counts.expired8h, counts.removed,
            histories.values().stream().mapToLong(IncrementalPingHistory::size).sum(),
            counts.queryNanos / 1000000, Math.max(0, duration - counts.queryNanos) / 1000000,
            duration / 1000000, duration > 10000000000L, cursor, counts.resetReason,
            availabilityHistories.size(), availabilityHistories.values().stream().mapToLong(AvailabilityHistory::bucketCount).sum(),
            counts.availabilityNanos / 1000000, snapshot.load().phase(), snapshot.load().loadedFrom(),
            snapshot.load().targetFrom(), confirmedBoundary
        );
    }

    private long publish(long reference, String version) {
        Long loadedFrom = bootstrap == null
            ? Long.valueOf(reference - Math.max(analyzer.lookbackMs(), availabilityRetention)) : bootstrap.loadedFrom();
        if (loadedFrom != null && loadedFrom > reference) loadedFrom = null;
        IndicatorLoadStatus load = new IndicatorLoadStatus(
            bootstrap != null ? "LOADING_HISTORY" : cursor < confirmedBoundary ? "CATCHING_UP" : "COMPLETE",
            loadedFrom,
            reference, reference - Math.max(analyzer.lookbackMs(), availabilityRetention), cursor, confirmedBoundary);
        Map<Key, PacketLossAnalysis> next = new HashMap<>();
        histories.forEach((key, history) -> next.put(key, analyzer.analyze(history, reference)));
        long availabilityStarted = System.nanoTime();
        Map<Key, Map<Integer, HostAvailabilityService.Report>> reports = new HashMap<>();
        availabilityHistories.forEach((key, history) -> reports.put(key,
            history.reports(reference, availabilityRetention, version, true, load)));
        snapshot = new Snapshot(Map.copyOf(next), Map.copyOf(reports),
            new AvailabilityHistory().reports(reference, availabilityRetention, version, true, load), load);
        return System.nanoTime() - availabilityStarted;
    }

    private void restoreCheckpoint(long now, long boundary) {
        sourceEpoch = checkpoints.epoch();
        for (var h : checkpoints.candidates()) {
            if (!compatibility.equals(h.compatibility()) || !sourceEpoch.equals(h.epoch())
                || h.cursor() > boundary || h.reference() > now) {
                CYCLE_LOG.warn("event=CHECKPOINT_REJECTED checkpoint={} reason=incompatible_or_source_changed", h.id());
                continue;
            }
            long started = System.nanoTime();
            CYCLE_LOG.info("event=CHECKPOINT_RESTORE_STARTED checkpoint={} cursor={}", h.id(), h.cursor());
            try {
                long[] count = {0}, progressAt = {System.nanoTime()};
                checkpoints.restore(h, part -> {
                    Key key = new Key(part.host(), part.target());
                    switch (part.kind()) {
                        case "indicator" -> histories.computeIfAbsent(key, k -> analyzer.newIncrementalHistory())
                            .restoreBlock(part.payload(), part.host(), part.target());
                        case "last" -> CheckpointCodec.decode(part.payload(), in -> {
                            var history = histories.get(key);
                            if (history == null) throw new java.io.IOException("Missing indicator history");
                            history.restoreLast(CheckpointCodec.sample(in));
                        });
                        case "availability" -> availabilityHistories.computeIfAbsent(key, k -> new AvailabilityHistory()).restoreBlock(part.payload());
                        case "pending" -> CheckpointCodec.decode(part.payload(), in -> {
                            int n = CheckpointCodec.count(in, 1000);
                            for (int i=0; i<n; i++) future.add(CheckpointCodec.sample(in));
                        });
                        default -> throw new IllegalArgumentException("Unknown checkpoint part");
                    }
                    count[0]++;
                    if (System.nanoTime()-progressAt[0] >= 10_000_000_000L) {
                        CYCLE_LOG.info("event=CHECKPOINT_RESTORE_PROGRESS checkpoint={} parts={} total_parts={}", h.id(), count[0], h.parts());
                        progressAt[0] = System.nanoTime();
                    }
                });
                histories.values().forEach(history -> analyzer.advance(history, h.reference()));
                cursor = h.cursor(); evaluatedAt = h.reference();
                publish(evaluatedAt, runId + ":restored:" + h.id());
                CYCLE_LOG.info("event=CHECKPOINT_RESTORED checkpoint={} cursor={} reference_at={} total_ms={}",
                    h.id(), cursor, java.time.Instant.ofEpochMilli(evaluatedAt), elapsedMillis(started));
                return;
            } catch (IllegalArgumentException invalid) {
                reset();
                checkpoints.discardInvalid(h.id());
                CYCLE_LOG.warn("event=CHECKPOINT_REJECTED checkpoint={} reason=invalid_payload", h.id());
            }
        }
        CYCLE_LOG.info("event=CHECKPOINT_UNAVAILABLE reason=no_usable_generation");
    }

    // Called under the cycle lock. Encoded arrays are immutable and reused until their block changes.
    synchronized AnalyticsCheckpointStore.Capture captureCheckpoint() {
        if (evaluatedAt == Long.MIN_VALUE || bootstrap != null || cursor < confirmedBoundary)
            throw new IllegalStateException("Cannot persist an incomplete indicator load");
        List<AnalyticsCheckpointStore.Part> parts = new ArrayList<>();
        histories.forEach((key, history) -> {
            int ordinal=0;
            for (var bytes : history.checkpointBlocks()) parts.add(AnalyticsCheckpointStore.Part.of("indicator", key.hostId(), key.target(), ordinal++, bytes));
            var last = history.last();
            if (last != null) parts.add(AnalyticsCheckpointStore.Part.of("last", key.hostId(), key.target(), 0,
                () -> CheckpointCodec.encode(out -> CheckpointCodec.sample(out, last))));
        });
        availabilityHistories.forEach((key, history) -> {
            int ordinal=0;
            for (var bytes : history.checkpointBlocks()) parts.add(AnalyticsCheckpointStore.Part.of("availability", key.hostId(), key.target(), ordinal++, bytes));
        });
        var pending = new ArrayList<>(future);
        for (int offset=0; offset<pending.size(); offset+=1000) {
            var group = pending.subList(offset, Math.min(offset+1000, pending.size()));
            parts.add(AnalyticsCheckpointStore.Part.of("pending", "", "", offset/1000, () -> CheckpointCodec.encode(out -> {
                out.writeInt(group.size()); for (var sample : group) CheckpointCodec.sample(out, sample);
            })));
        }
        return new AnalyticsCheckpointStore.Capture(new AnalyticsCheckpointStore.Header(UUID.randomUUID().toString(),
            evaluatedAt, cursor, compatibility, sourceEpoch, parts.size(), ""), List.copyOf(parts));
    }

    private void scheduleCheckpoint(long now) {
        if (bootstrap != null || cursor < confirmedBoundary || checkpoints == null || (checkpointWrite != null && !checkpointWrite.isDone())
            || (lastCheckpointAttempt != Long.MIN_VALUE && now - lastCheckpointAttempt < checkpointInterval)) return;
        lastCheckpointAttempt = now;
        try {
            long started = System.nanoTime();
            CYCLE_LOG.info("event=CHECKPOINT_CAPTURE_STARTED cursor={}", cursor);
            var capture = captureCheckpoint();
            CYCLE_LOG.info("event=CHECKPOINT_CAPTURE_COMPLETED checkpoint={} parts={} total_ms={}", capture.header().id(), capture.parts().size(), elapsedMillis(started));
            checkpointWrite = checkpointWriter.submit(() -> {
                long writeStarted = System.nanoTime();
                try {
                    CYCLE_LOG.info("event=CHECKPOINT_WRITE_STARTED checkpoint={}", capture.header().id());
                    checkpoints.save(capture);
                    CYCLE_LOG.info("event=CHECKPOINT_SAVED checkpoint={} cursor={} parts={} bytes={} total_ms={}",
                        capture.header().id(), capture.header().cursor(), capture.parts().size(),
                        capture.parts().stream().mapToLong(p -> p.payload().length).sum(), elapsedMillis(writeStarted));
                } catch (RuntimeException e) {
                    CYCLE_LOG.error("event=CHECKPOINT_WRITE_FAILED checkpoint={} error={}", capture.header().id(), e.getClass().getSimpleName());
                }
            });
        } catch (RuntimeException e) {
            CYCLE_LOG.error("event=CHECKPOINT_CAPTURE_FAILED error={}", e.getClass().getSimpleName());
        }
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
        bootstrap = null;
        confirmedBoundary = 0;
        evaluatedAt = Long.MIN_VALUE;
    }

    public IndicatorLoadStatus loadStatus() { return snapshot.load(); }

    public HostAvailabilityService.Report availability(Host host, int hours) {
        var published = snapshot;
        return published.availability().getOrDefault(new Key(host.id(), host.ip()), published.emptyReports()).get(hours);
    }

    /** Indicators and coverage from the same immutable publication. */
    public record Current(Map<String, PacketLossAnalysis> indicators, IndicatorLoadStatus load) {}

    public Current currentSnapshot(List<Host> hosts) {
        var published = snapshot;
        Map<String, PacketLossAnalysis> result = new HashMap<>();
        for (Host host : hosts) {
            result.put(host.id(), published.indicators().getOrDefault(new Key(host.id(), host.ip()), empty));
        }
        return new Current(Map.copyOf(result), published.load());
    }

    public Map<String, PacketLossAnalysis> current(List<Host> hosts) {
        return currentSnapshot(hosts).indicators();
    }
}
