package br.com.scriptping.aggregation;

import br.com.scriptping.Host;
import br.com.scriptping.aggregation.MinuteAggregationService.Key;
import br.com.scriptping.aggregation.MinuteAggregationService.Summary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

/** Uses the aggregation transaction/lock: summaries, rollups and cursor commit together. */
final class HistoricalAggregation {
    private static final long HOUR = 3_600_000L, DAY = 86_400_000L;
    private final JdbcTemplate jdbc;

    HistoricalAggregation(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    void initialize() {
        if (ready()) return;
        // A restart can safely retry: the flag and the backfill commit atomically.
        jdbc.update("DELETE FROM ping_rollup");
        for (long resolution : new long[]{HOUR, DAY}) {
            jdbc.update("""
                INSERT INTO ping_rollup
                (host_id,target,resolution,bucket_at,total_pings,healthy,degraded,lost,errors,
                 latency_sum,last_sample_at,valid_minutes)
                SELECT host_id,target,%1$d,CAST(FLOOR(minute_at / CAST(%1$d AS NUMERIC)) AS BIGINT) * %1$d,
                    SUM(total_pings),SUM(healthy),SUM(degraded),SUM(lost),SUM(errors),
                    SUM(latency_sum),MAX(last_sample_at),
                    SUM(CASE WHEN healthy+degraded+lost > 0 THEN 1 ELSE 0 END)
                FROM ping_minute GROUP BY host_id,target,
                    CAST(FLOOR(minute_at / CAST(%1$d AS NUMERIC)) AS BIGINT) * %1$d
                """.formatted(resolution));
        }
        jdbc.update("UPDATE ping_aggregation_progress SET rollups_ready=TRUE WHERE id=1");
    }

    boolean ready() {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT rollups_ready FROM ping_aggregation_progress WHERE id=1", Boolean.class));
    }

    record RollupKey(String host, String target, long resolution, long at) {}
    void add(Map<MinuteKey, Summary> contributions, Map<MinuteKey, PingMinute> existing) {
        var totals = new HashMap<RollupKey, Summary>();
        var valid = new HashMap<RollupKey, Long>();
        contributions.forEach((minute, summary) -> {
            var before = existing.get(minute);
            boolean wasValid = before != null && before.snapshot().metrics().total() > 0;
            long newlyValid = !wasValid && summary.metrics().total() > 0 ? 1 : 0;
            for (long resolution : new long[]{HOUR, DAY}) {
                var key = new RollupKey(summary.host(), summary.target(), resolution,
                    Math.floorDiv(summary.minute(), resolution) * resolution);
                totals.merge(key, summary, MinuteAggregationService::combine);
                valid.merge(key, newlyValid, Long::sum);
            }
        });
        totals.forEach((key, s) -> {
            int changed = jdbc.update("""
                UPDATE ping_rollup SET total_pings=total_pings+?,healthy=healthy+?,degraded=degraded+?,
                    lost=lost+?,errors=errors+?,latency_sum=latency_sum+?,
                    last_sample_at=GREATEST(last_sample_at,?),valid_minutes=valid_minutes+?
                WHERE host_id=? AND target=? AND resolution=? AND bucket_at=?
                """, s.total(),s.healthy(),s.degraded(),s.lost(),s.errors(),s.sum(),s.last(),valid.get(key),
                key.host(),key.target(),key.resolution(),key.at());
            if (changed == 0) jdbc.update("""
                INSERT INTO ping_rollup(host_id,target,resolution,bucket_at,total_pings,healthy,
                    degraded,lost,errors,latency_sum,last_sample_at,valid_minutes)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                """,key.host(),key.target(),key.resolution(),key.at(),s.total(),s.healthy(),s.degraded(),
                s.lost(),s.errors(),s.sum(),s.last(),valid.get(key));
        });
    }

    record Result(Map<Key, Summary> totals, long validHostMinutes) {}
    Result totals(long from, long to, List<Host> hosts, boolean accelerated) {
        var totals = new HashMap<Key, Summary>();
        long valid = 0;
        if (hosts.isEmpty()) return new Result(totals, 0);
        // Split at UTC storage boundaries. Calendar chart boundaries remain in the business timezone.
        long at = from;
        while (at < to) {
            long resolution = accelerated && at % DAY == 0 && to - at >= DAY ? DAY
                : accelerated && at % HOUR == 0 && to - at >= HOUR ? HOUR : 60_000L;
            long end;
            if (resolution == DAY) end = Math.floorDiv(to, DAY) * DAY;
            else if (resolution == HOUR) end = Math.min(Math.floorDiv(to, HOUR) * HOUR,
                (Math.floorDiv(at, DAY) + 1) * DAY);
            else end = accelerated ? Math.min(to, (Math.floorDiv(at, HOUR) + 1) * HOUR) : to;
            valid += read(at, end, resolution, hosts, totals);
            at = end;
        }
        return new Result(totals, valid);
    }

    private long read(long from, long to, long resolution, List<Host> hosts, Map<Key, Summary> totals) {
        boolean minute = resolution == 60_000L;
        String table = minute ? "ping_minute" : "ping_rollup";
        String time = minute ? "minute_at" : "bucket_at";
        String coverage = minute ? "CASE WHEN healthy+degraded+lost>0 THEN 1 ELSE 0 END" : "valid_minutes";
        var args = new ArrayList<Object>();
        args.add(from); args.add(to);
        if (!minute) args.add(resolution);
        var keys = hosts.stream().map(h -> new Key(h.id(),h.ip())).distinct().toList();
        String filter = String.join(" OR ", Collections.nCopies(keys.size(), "(host_id=? AND target=?)"));
        keys.forEach(k -> { args.add(k.host()); args.add(k.target()); });
        String sql = "SELECT host_id,target,SUM(total_pings),SUM(healthy),SUM(degraded),SUM(lost),"
            + "SUM(errors),SUM(latency_sum),MAX(last_sample_at),SUM(" + coverage + ") FROM " + table
            + " WHERE " + time + ">=? AND " + time + "<?" + (minute ? "" : " AND resolution=?")
            + " AND (" + filter + ") GROUP BY host_id,target";
        long[] valid = {0};
        jdbc.query(sql, rs -> {
            var s = new Summary(rs.getString(1),rs.getString(2),0,rs.getLong(3),rs.getLong(4),
                rs.getLong(5),rs.getLong(6),rs.getLong(7),rs.getDouble(8),rs.getLong(9));
            totals.merge(s.key(),s,MinuteAggregationService::combine);
            valid[0] += rs.getLong(10);
        }, args.toArray());
        return valid[0];
    }

    record Segment(int index, long from, long to, long resolution) {}

    /** At most three queries per period, regardless of host or chart interval count. */
    List<MinuteAggregationService.HistoricalBucket> buckets(
        List<br.com.scriptping.availability.AvailabilityPeriod.Interval> intervals,
        List<Host> hosts, boolean accelerated) {
        var result = new ArrayList<MinuteAggregationService.HistoricalBucket>();
        if (hosts.isEmpty() || intervals.isEmpty()) return result;
        var segments = new ArrayList<Segment>();
        for (int index = 0; index < intervals.size(); index++) {
            var interval = intervals.get(index);
            long at = interval.from();
            while (at < interval.to()) {
                long resolution = accelerated && at % DAY == 0 && interval.to() - at >= DAY ? DAY
                    : accelerated && at % HOUR == 0 && interval.to() - at >= HOUR ? HOUR : 60_000L;
                long end = resolution == DAY ? Math.floorDiv(interval.to(), DAY) * DAY
                    : resolution == HOUR ? Math.min(Math.floorDiv(interval.to(), HOUR) * HOUR,
                        (Math.floorDiv(at, DAY) + 1) * DAY)
                    : accelerated ? Math.min(interval.to(), (Math.floorDiv(at, HOUR) + 1) * HOUR) : interval.to();
                segments.add(new Segment(index, at, end, resolution));
                at = end;
            }
        }
        for (long resolution : new long[]{60_000L, HOUR, DAY}) {
            var selected = segments.stream().filter(s -> s.resolution() == resolution).toList();
            if (selected.isEmpty()) continue;
            String time = resolution == 60_000L ? "minute_at" : "bucket_at";
            String table = resolution == 60_000L ? "ping_minute" : "ping_rollup";
            String coverage = resolution == 60_000L
                ? "CASE WHEN healthy+degraded+lost>0 THEN 1 ELSE 0 END" : "valid_minutes";
            // Boundaries come only from server-generated calendar intervals, never request text.
            String cases = selected.stream().map(s -> " WHEN " + time + ">=" + s.from()
                + " AND " + time + "<" + s.to() + " THEN " + s.index()).collect(java.util.stream.Collectors.joining());
            String ranges = selected.stream().map(s -> "(" + time + ">=" + s.from()
                + " AND " + time + "<" + s.to() + ")").collect(java.util.stream.Collectors.joining(" OR "));
            var args = new ArrayList<Object>();
            String filter = hostFilter(hosts, args);
            String sql = "SELECT interval_id,host_id,target,SUM(total_pings),SUM(healthy),SUM(degraded),"
                + "SUM(lost),SUM(errors),SUM(latency_sum),MAX(last_sample_at),SUM(coverage) FROM ("
                + "SELECT CASE" + cases + " END AS interval_id,host_id,target,total_pings,healthy,degraded,"
                + "lost,errors,latency_sum,last_sample_at," + coverage + " AS coverage FROM " + table
                + " WHERE (" + ranges + ")" + (resolution == 60_000L ? "" : " AND resolution=" + resolution)
                + " AND (" + filter + ")) selected GROUP BY interval_id,host_id,target";
            jdbc.query(sql, rs -> {
                var summary = new Summary(rs.getString(2), rs.getString(3), 0, rs.getLong(4),
                    rs.getLong(5), rs.getLong(6), rs.getLong(7), rs.getLong(8), rs.getDouble(9), rs.getLong(10));
                result.add(new MinuteAggregationService.HistoricalBucket(rs.getInt(1), summary, rs.getLong(11)));
            }, args.toArray());
        }
        return result;
    }

    private String hostFilter(List<Host> hosts, List<Object> args) {
        var keys = hosts.stream().map(h -> new Key(h.id(), h.ip())).distinct().toList();
        keys.forEach(k -> { args.add(k.host()); args.add(k.target()); });
        return String.join(" OR ", Collections.nCopies(keys.size(), "(host_id=? AND target=?)"));
    }

    Map<Key, Long> earliestByHost(List<Host> hosts) {
        var result = new HashMap<Key, Long>();
        if (hosts.isEmpty()) return result;
        var args = new ArrayList<Object>();
        var keys = hosts.stream().map(h -> new Key(h.id(), h.ip())).distinct().toList();
        keys.forEach(k -> { args.add(k.host()); args.add(k.target()); });
        String values = String.join(",", Collections.nCopies(keys.size(),
            "(CAST(? AS VARCHAR(255)),CAST(? AS VARCHAR(255)))"));
        // One round trip, with an indexed MIN lookup per endpoint. A GROUP BY over the
        // full two-year minute history would otherwise scan every retained minute.
        jdbc.query("SELECT selected.host_id,selected.target,(SELECT MIN(m.minute_at) FROM ping_minute m"
            + " WHERE m.host_id=selected.host_id AND m.target=selected.target) FROM (VALUES "
            + values + ") AS selected(host_id,target)", rs -> {
                Long first = rs.getObject(3, Long.class);
                if (first != null) result.put(new Key(rs.getString(1), rs.getString(2)), first);
            }, args.toArray());
        return Map.copyOf(result);
    }

    long earliest(List<Host> hosts, long fallback) {
        return earliestByHost(hosts).values().stream().mapToLong(Long::longValue).min().orElse(fallback);
    }
}
