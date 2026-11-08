package br.com.scriptping.aggregation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import br.com.scriptping.zabbix.Host;
import br.com.scriptping.ingestion.PingSample;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MinuteAggregationService {

    static final long MINUTE = 60_000L;
    private static final int MINUTE_LOOKUP_BATCH_SIZE = 500;
    public record Key(String host, String target) {}
    public record Progress(long cursor, long boundary, Long processedAt, long completeThrough, long rawCursor) {
        public Progress(long cursor, long boundary, Long processedAt, long completeThrough) {
            this(cursor, boundary, processedAt, completeThrough, cursor);
        }
    }
    public record Batch(long cursor, long boundary, long rows, int summaries) {}
    public record Summary(String host, String target, long minute, long total, long healthy,
                   long degraded, long lost, long errors, double sum, long last) {
        public Key key() { return new Key(host, target); }
        public br.com.scriptping.availability.HostAvailabilityService.Metrics metrics() {
            var c = new br.com.scriptping.availability.HostAvailabilityService.Counts();
            c.h = healthy;
            c.d = degraded;
            c.p = lost;
            c.e = errors;
            c.sum = sum;
            return c.metrics();
        }
    }

    private final MinuteAggregationRepository repository;
    private final HistoricalAggregation history;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public MinuteAggregationService(MinuteAggregationRepository repository, org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.history = new HistoricalAggregation(jdbc);
        this.jdbc = jdbc;
        this.repository = repository;
    }

    public Progress progress() {
        return repository.progress().snapshot();
    }

    public long legacyBoundary() {
        return Math.max(repository.latestSampleId(), jdbc.queryForObject(
            "SELECT raw_cursor_id FROM ping_aggregation_progress WHERE id=1", Long.class));
    }

    /** Hold the ingestion guard only while capturing the committed boundary. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long committedBoundary() {
        repository.lockIngestion();
        return legacyBoundary();
    }

    @Transactional
    public Batch aggregate(long boundary, int pageSize, long now) {
        if (pageSize < 1) {
            throw new IllegalArgumentException("Page size must be positive");
        }

        var checkpoint = repository.lockProgress();
        Progress previous = checkpoint.snapshot();
        history.initialize();
        if (boundary < previous.rawCursor()) {
            return new Batch(previous.rawCursor(), boundary, 0, 0);
        }

        var samples = repository.samples(previous.rawCursor(), boundary, PageRequest.of(0, pageSize));
        var contributions = new HashMap<MinuteKey, Summary>();
        for (PingSample sample : samples) {
            long minute = minuteAt(sample.measuredAt());
            var key = new MinuteKey(new HostTarget(sample.hostId(), sample.target()), minute);
            contributions.merge(key, summarize(sample, minute), MinuteAggregationService::combine);
        }

        var existing = new HashMap<MinuteKey, PingMinute>();
        var keys = new ArrayList<>(contributions.keySet());
        for (int start = 0; start < keys.size(); start += MINUTE_LOOKUP_BATCH_SIZE) {
            int end = Math.min(start + MINUTE_LOOKUP_BATCH_SIZE, keys.size());
            for (PingMinute stored : repository.minutes(keys.subList(start, end))) {
                existing.put(stored.getId(), stored);
            }
        }
        long through = samples.isEmpty() ? boundary : samples.getLast().id();
        long revision = through > previous.rawCursor() ? Math.max(previous.cursor() + 1, through) : previous.cursor();
        recordChanges(revision, contributions, existing);
        history.add(contributions, existing);
        var additions = new ArrayList<PingMinute>();
        contributions.forEach((key, contribution) -> {
            var stored = existing.get(key);
            if (stored == null) {
                additions.add(new PingMinute(key, contribution));
            } else {
                stored.setSummary(combine(stored.snapshot(), contribution));
            }
        });
        repository.saveAll(additions);

        long complete = through == boundary
            ? Math.max(previous.completeThrough(), minuteAt(now))
            : previous.completeThrough();
        long processedAt = previous.processedAt() == null ? now : Math.max(now, previous.processedAt());
        checkpoint.setProgress(new Progress(revision, Math.max(revision, boundary), processedAt, complete, through));
        return new Batch(through, boundary, samples.size(), contributions.size());
    }

    /** Called by the ingestion transaction while holding the ingestion guard. */
    @Transactional
    public void ingest(List<PingSample> samples, long now) {
        // Finish legacy raw history before allocating revisions for direct ingestion.
        var checkpoint = repository.lockProgress();
        var previous = checkpoint.snapshot();
        long boundary = Math.max(repository.latestSampleId(), previous.rawCursor());
        while (previous.rawCursor() < boundary) {
            aggregate(boundary, 10000, now);
            previous = checkpoint.snapshot();
        }
        history.initialize();
        long revision = previous.cursor() + 1;
        var contributions = new HashMap<MinuteKey, Summary>();
        var extremes = new HashMap<MinuteKey, double[]>();
        for (var sample : samples) {
            long minute = minuteAt(sample.measuredAt());
            var key = new MinuteKey(new HostTarget(sample.hostId(), sample.target()), minute);
            contributions.merge(key, summarize(sample, minute), MinuteAggregationService::combine);
            if (sample.outcome() == PingSample.SUCCESS) {
                var range = extremes.computeIfAbsent(key,
                    ignored -> new double[]{sample.latency(), sample.latency()});
                range[0] = Math.min(range[0], sample.latency());
                range[1] = Math.max(range[1], sample.latency());
            }
        }
        var existing = new HashMap<MinuteKey, PingMinute>();
        var keys = new ArrayList<>(contributions.keySet());
        for (int start = 0; start < keys.size(); start += MINUTE_LOOKUP_BATCH_SIZE) {
            for (var stored : repository.minutes(keys.subList(start,
                Math.min(start + MINUTE_LOOKUP_BATCH_SIZE, keys.size())))) existing.put(stored.getId(), stored);
        }
        recordChanges(revision, contributions, existing);
        history.add(contributions, existing);
        var additions = new ArrayList<PingMinute>();
        contributions.forEach((key, contribution) -> {
            var stored = existing.get(key);
            if (stored == null) additions.add(new PingMinute(key, contribution));
            else stored.setSummary(combine(stored.snapshot(), contribution));
        });
        repository.saveAllAndFlush(additions);
        var ranges = new ArrayList<Object[]>();
        extremes.forEach((key, range) -> ranges.add(new Object[]{range[0], range[0], range[1], range[1],
            key.endpoint().host(), key.endpoint().target(), key.minute(),
            contributions.get(key).healthy() + contributions.get(key).degraded()}));
        if (!ranges.isEmpty()) jdbc.batchUpdate("""
            UPDATE ping_minute SET latency_min=CASE WHEN latency_min IS NULL THEN ? ELSE LEAST(latency_min,?) END,
                latency_max=CASE WHEN latency_max IS NULL THEN ? ELSE GREATEST(latency_max,?) END
            WHERE host_id=? AND target=? AND minute_at=?
                AND (latency_min IS NOT NULL OR healthy+degraded=?)
            """, ranges);
        checkpoint.setProgress(new Progress(revision, revision, now,
            Math.max(previous.completeThrough(), minuteAt(now)), previous.rawCursor()));
    }

    private void recordChanges(long revision, Map<MinuteKey, Summary> contributions,
        Map<MinuteKey, PingMinute> existing) {
        var arguments = new ArrayList<Object[]>();
        contributions.forEach((key, s) -> {
            var before = existing.get(key);
            boolean wasValid = before != null && before.snapshot().metrics().total() > 0;
            long valid = !wasValid && s.metrics().total() > 0 ? 1 : 0;
            arguments.add(new Object[]{revision, s.host(), s.target(), s.minute(), s.healthy(),
                s.degraded(), s.lost(), s.errors(), s.sum(), s.last(), valid});
        });
        if (!arguments.isEmpty()) jdbc.batchUpdate("""
            INSERT INTO availability_change(revision,host_id,target,minute_at,healthy,degraded,lost,
                errors,latency_sum,last_sample_at,valid_minutes) VALUES(?,?,?,?,?,?,?,?,?,?,?)
            """, arguments);
    }

    public record Change(Summary delta, long validMinutes) {}
    public record ChangeSet(long floor, List<Change> changes, boolean truncated) {}

    /** Bounded replay; a very old/missing checkpoint falls back to a controlled rebuild. */
    public ChangeSet changesSince(long after, long through) {
        long floor = jdbc.queryForObject("SELECT floor_revision FROM availability_change_state WHERE id=1", Long.class);
        if (after < floor) return new ChangeSet(floor, List.of(), false);
        var rows = jdbc.query("""
            SELECT host_id,target,minute_at,SUM(healthy),SUM(degraded),SUM(lost),SUM(errors),
                SUM(latency_sum),MAX(last_sample_at),SUM(valid_minutes)
            FROM availability_change WHERE revision>? AND revision<=?
            GROUP BY host_id,target,minute_at LIMIT 200001
            """, (rs, row) -> {
                long h=rs.getLong(4), d=rs.getLong(5), l=rs.getLong(6), e=rs.getLong(7);
                return new Change(new Summary(rs.getString(1),rs.getString(2),rs.getLong(3),
                    h+d+l+e,h,d,l,e,rs.getDouble(8),rs.getLong(9)),rs.getLong(10));
            }, after, through);
        return new ChangeSet(floor, List.copyOf(rows), rows.size()>200000);
    }

    public Map<Key, Summary> totals(long from, long to, List<Host> hosts) {
        if (hosts.isEmpty()) {
            return Map.of();
        }
        var result = new HashMap<Key, Summary>();
        for (Summary summary : repository.totals(from, to, keys(hosts))) {
            result.put(summary.key(), summary);
        }
        return result;
    }

    public Map<Long, Summary> series(long from, long to, List<Host> hosts) {
        if (hosts.isEmpty()) {
            return Map.of();
        }
        var result = new HashMap<Long, Summary>();
        for (Summary summary : repository.series(from, to, keys(hosts))) {
            result.put(summary.minute(), summary);
        }
        return result;
    }

    public record HistoricalResult(Map<Key, Summary> totals, long validHostMinutes) {}
    public HistoricalResult historicalTotals(long from, long to, List<Host> hosts, boolean accelerated) {
        var result = history.totals(from, to, hosts, accelerated);
        return new HistoricalResult(result.totals(), result.validHostMinutes());
    }
    public record HistoricalBucket(int index, Summary summary, long validHostMinutes) {}
    public List<HistoricalBucket> historicalBuckets(
        List<br.com.scriptping.availability.AvailabilityPeriod.Interval> intervals,
        List<Host> hosts, boolean accelerated) {
        return history.buckets(intervals, hosts, accelerated);
    }
    public Map<Key, Long> earliestByHost(List<Host> hosts) { return history.earliestByHost(hosts); }

    public boolean historyReady() { return history.ready(); }
    public long earliest(List<Host> hosts, long fallback) { return history.earliest(hosts, fallback); }

    private List<HostTarget> keys(List<Host> hosts) {
        return hosts.stream().map(host -> new HostTarget(host.id(), host.ip())).distinct().toList();
    }

    private static long minuteAt(long timestamp) {
        return Math.floorDiv(timestamp, MINUTE) * MINUTE;
    }

    private static Summary summarize(PingSample sample, long minute) {
        byte outcome = sample.outcome();
        double latency = outcome == PingSample.SUCCESS ? sample.latency() : 0;
        return new Summary(sample.hostId(), sample.target(), minute, 1,
            outcome == PingSample.SUCCESS && latency <= sample.latencyLimit() ? 1 : 0,
            outcome == PingSample.SUCCESS && latency > sample.latencyLimit() ? 1 : 0,
            outcome == PingSample.LOSS ? 1 : 0,
            outcome == PingSample.ERROR ? 1 : 0,
            latency, sample.measuredAt());
    }

    public static Summary combine(Summary left, Summary right) {
        return new Summary(left.host(), left.target(), left.minute(),
            left.total() + right.total(), left.healthy() + right.healthy(),
            left.degraded() + right.degraded(), left.lost() + right.lost(),
            left.errors() + right.errors(), left.sum() + right.sum(), Math.max(left.last(), right.last()));
    }
}
