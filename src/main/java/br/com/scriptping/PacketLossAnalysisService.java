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
    private int pageSize = 10000;
    private long confirmedBoundary;

    @org.springframework.beans.factory.annotation.Autowired
    void configureIncremental(@Value("${indicators.incremental.page-size:10000}") int pageSize,
        @Value("${indicators.incremental.delay-ms:1000}") long delayMs) {
        if (delayMs < 1) throw new IllegalArgumentException("Incremental delay must be positive");
        if (pageSize < 1 || pageSize > 100000) throw new IllegalArgumentException("Incremental page size must be between 1 and 100000");
        this.pageSize = pageSize;
    }

    // Drain new IDs in bounded pages between regular publication cycles.
    @Scheduled(fixedDelayString = "${indicators.incremental.delay-ms:1000}", scheduler = "indicatorScheduler")
    public synchronized void continueIncremental() {
        if (cursor < confirmedBoundary) refreshAt(System.currentTimeMillis());
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
        compatibility = CheckpointCodec.hash(("v2|" + interval + "|" + timeout + "|" + availabilityRetention
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

    // Page failures preserve committed working state; readers retain the last publication.
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
                cursor = boundary; // Cold start: only IDs committed after this boundary are collected.
            }
            if (counts.initial) CYCLE_LOG.info("event=INITIAL_LOAD_STARTED run_id={} cycle={} boundary={}", runId, cycle, boundary);
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
            // Expiration is committed independently of ingestion, including on a failed query.
            evaluatedAt = now;
            counts.stage = "database_query";
            long liveThrough = cursor + Math.min((long) pageSize, boundary - cursor);
            if (liveThrough > cursor) {
                var page = new ArrayList<PingSample>();
                queryStarted = System.nanoTime();
                try { store.readIncrement(cursor, liveThrough, cutoff, page::add); }
                finally { counts.queryNanos += System.nanoTime() - queryStarted; }
                counts.stage = "sample_processing";
                applyPage(page, now, cutoff, counts);
                counts.late += page.stream().filter(sample -> sample.measuredAt() >= cutoff
                    && sample.measuredAt() < previousReference && IpAddress.isValid(sample.target())).count();
                cursor = liveThrough;
            }
            counts.stage = "pending_processing";
            // Keep pending samples until their entire batch has committed.
            var ready = future.stream().filter(sample -> sample.measuredAt() <= now).toList();
            applyPage(ready, now, cutoff, counts);
            counts.fetched -= ready.size();
            counts.released += ready.size();
            future.removeAll(new HashSet<>(ready));
            counts.stage = "publication";
            counts.availabilityNanos = publish(now, runId + ":" + cycle);
            evaluatedAt = now;
            lastPublishedCycle = cycle;
        } catch (RuntimeException failure) {
            // Queries are buffered and each page is atomic. Keep all committed pages.
            CYCLE_LOG.error(
                "event=CYCLE_FAILED run_id={} cycle={} reference_at={} mode={} stage={} error={} fetched={} incorporated={} discarded={} total_ms={} indicators_published=false last_published_cycle={} next_cycle=retry",
                runId, cycle, java.time.Instant.ofEpochMilli(now), counts.initial ? "initial_load" : "incremental",
                counts.stage, failure.getClass().getSimpleName(), counts.fetched, counts.incorporated,
                counts.discarded, elapsedMillis(started), lastPublishedCycle, failure
            );
            throw failure;
        }
        scheduleCheckpoint(now);
        long duration = System.nanoTime() - started;
        CYCLE_LOG.info(
            "event={} run_id={} cycle={} reference_at={} hosts={} fetched={} incorporated={} late_arrivals={} future_queued={} future_released={} pending_future={} discarded={} expired_5m={} expired_1h={} expired_8h={} removed_pings={} retained_pings={} query_ms={} processing_ms={} total_ms={} schedule_overrun={} cursor={} reset_reason={} availability_hosts={} availability_buckets={} availability_ms={} indicators_published=true load_phase={} loaded_from={} target_from={} boundary={}",
            counts.initial ? "INITIAL_LOAD_COMPLETED" : "CYCLE_COMPLETED", runId, cycle,
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
        IndicatorLoadStatus load = new IndicatorLoadStatus(
            cursor < confirmedBoundary ? "CATCHING_UP" : "COMPLETE",
            null, reference, reference - Math.max(analyzer.lookbackMs(), availabilityRetention), cursor, confirmedBoundary);
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
                boolean[] progressSeen = {false};
                checkpoints.restore(h, part -> {
                    Key key = new Key(part.host(), part.target());
                    switch (part.kind()) {
                        case "progress" -> CheckpointCodec.decode(part.payload(), in -> {
                            if (progressSeen[0]) throw new java.io.IOException("Duplicate progress");
                            progressSeen[0] = true;
                            confirmedBoundary = in.readLong();
                            if (confirmedBoundary < h.cursor() || confirmedBoundary > boundary)
                                throw new java.io.IOException("Invalid confirmed boundary");
                        });
                        // Legacy checkpoints may contain an unfinished historical cursor.
                        // Keep their aggregates and live cursor, but never resume that reader.
                        case "bootstrap" -> { }
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
                if (!progressSeen[0]) throw new IllegalArgumentException("Missing load progress");
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
            } catch (RuntimeException failure) {
                reset();
                throw failure;
            }
        }
        CYCLE_LOG.info("event=CHECKPOINT_UNAVAILABLE reason=no_usable_generation");
    }

    // Called under the cycle lock. Encoded arrays are immutable and reused until their block changes.
    synchronized AnalyticsCheckpointStore.Capture captureCheckpoint() {
        if (evaluatedAt == Long.MIN_VALUE)
            throw new IllegalStateException("Cannot persist an uninitialized indicator load");
        List<AnalyticsCheckpointStore.Part> parts = new ArrayList<>();
        byte[] progress = CheckpointCodec.encode(out -> out.writeLong(confirmedBoundary));
        parts.add(AnalyticsCheckpointStore.Part.of("progress", "", "", 0, () -> progress));
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
        if (evaluatedAt == Long.MIN_VALUE || checkpoints == null || (checkpointWrite != null && !checkpointWrite.isDone())
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

    /** Stage only affected histories. Readers and checkpoint capture cannot observe a partial page. */
    private void applyPage(List<PingSample> page, long now, long cutoff, Cycle counts) {
        Map<Key, IncrementalPingHistory> original = new HashMap<>();
        Map<Key, AvailabilityHistory> originalAvailability = new HashMap<>();
        List<PingSample> queued = new ArrayList<>();
        long incorporated = 0, discarded = 0;
        try {
            for (PingSample sample : page) {
                if (!IpAddress.isValid(sample.target()) || sample.measuredAt() < cutoff) { discarded++; continue; }
                if (sample.measuredAt() > now) { queued.add(sample); continue; }
                Key key = new Key(sample.hostId(), sample.target());
                if (!original.containsKey(key)) {
                    var history = histories.get(key);
                    original.put(key, history);
                    if (history != null) histories.put(key, history.fork());
                    var availability = availabilityHistories.get(key);
                    originalAvailability.put(key, availability);
                    if (availability != null) availabilityHistories.put(key, availability.fork());
                }
                incorporate(sample, now);
                incorporated++;
            }
        } catch (RuntimeException failure) {
            original.forEach((key, value) -> { if (value == null) histories.remove(key); else histories.put(key, value); });
            originalAvailability.forEach((key, value) -> { if (value == null) availabilityHistories.remove(key); else availabilityHistories.put(key, value); });
            throw failure;
        }
        future.addAll(queued);
        counts.fetched += page.size(); counts.incorporated += incorporated;
        counts.discarded += discarded; counts.queued += queued.size();
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
        confirmedBoundary = 0;
        evaluatedAt = Long.MIN_VALUE;
    }

    public IndicatorLoadStatus loadStatus() { return snapshot.load(); }

    public HostAvailabilityService.Report availability(Host host, int hours) {
        var published = snapshot;
        return published.availability().getOrDefault(new Key(host.id(), host.ip()), published.emptyReports()).get(hours);
    }

    public record AvailabilitySnapshot(Map<String, Map<Integer, HostAvailabilityService.Report>> hosts,
        Map<Integer, HostAvailabilityService.Report> empty, Map<String, PacketLossAnalysis> indicators) {}

    public AvailabilitySnapshot availabilitySnapshot(List<Host> hosts) {
        var published = snapshot;
        Map<String, Map<Integer, HostAvailabilityService.Report>> result = new HashMap<>();
        for (Host host : hosts) result.put(host.id(), published.availability()
            .getOrDefault(new Key(host.id(), host.ip()), published.emptyReports()));
        Map<String, PacketLossAnalysis> indicators = new HashMap<>();
        for (Host host : hosts) indicators.put(host.id(), published.indicators().getOrDefault(new Key(host.id(), host.ip()), empty));
        return new AvailabilitySnapshot(Map.copyOf(result), published.emptyReports(), Map.copyOf(indicators));
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
