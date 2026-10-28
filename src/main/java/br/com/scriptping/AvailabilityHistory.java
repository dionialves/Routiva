package br.com.scriptping;

import java.util.*;
import static br.com.scriptping.HostAvailabilityService.*;

/** Minute totals updated on ingestion; compact contributions preserve exact moving-window edges. */
final class AvailabilityHistory {
    static final long MINUTE = 60_000L;
    private final NavigableMap<Long, Minute> minutes = new TreeMap<>();

    private static final class Minute {
        int[] offsets = new int[64];
        double[] latencies = new double[64];
        byte[] kinds = new byte[64]; // healthy, degraded, loss, internal error
        int size;
        long latest = Long.MIN_VALUE;
        final Counts total = new Counts();
        Point cached;

        void add(PingSample sample, long start) {
            if (size == offsets.length) {
                offsets = Arrays.copyOf(offsets, size * 2);
                latencies = Arrays.copyOf(latencies, size * 2);
                kinds = Arrays.copyOf(kinds, size * 2);
            }
            byte outcome = sample.outcome();
            byte kind = outcome == PingSample.SUCCESS
                ? (byte) (sample.latency() <= sample.latencyLimit() ? 0 : 1)
                : (byte) (outcome == PingSample.LOSS ? 2 : 3);
            offsets[size] = (int) (sample.measuredAt() - start);
            latencies[size] = sample.latency() == null ? 0 : sample.latency();
            kinds[size++] = kind;
            total.add(outcome, sample.latency(), sample.latencyLimit());
            latest = Math.max(latest, sample.measuredAt());
            cached = null;
        }

        Counts range(long start, long from, long to) {
            if (from <= start && to >= start + MINUTE) return total;
            Counts result = new Counts();
            for (int i = 0; i < size; i++) {
                long time = start + offsets[i];
                if (time < from || time >= to) continue;
                switch (kinds[i]) {
                    case 0 -> { result.h++; result.sum += latencies[i]; }
                    case 1 -> { result.d++; result.sum += latencies[i]; }
                    case 2 -> result.p++;
                    default -> result.e++;
                }
            }
            return result;
        }
    }

    void add(PingSample sample) {
        long start = Math.floorDiv(sample.measuredAt(), MINUTE) * MINUTE;
        minutes.computeIfAbsent(start, key -> new Minute()).add(sample, start);
    }

    void expire(long cutoff) {
        minutes.headMap(Math.floorDiv(cutoff, MINUTE) * MINUTE, false).clear();
    }

    boolean isEmpty() { return minutes.isEmpty(); }
    int bucketCount() { return minutes.size(); }

    Map<Integer, Report> reports(long now, long retention, String version, boolean initialized) {
        Map<Integer, Report> reports = new HashMap<>();
        long retainedFrom = now - retention;
        Long latest = null;
        // At most the current minute can contain an excluded sample exactly at the reference.
        for (var entry : minutes.descendingMap().entrySet()) {
            Minute minute = entry.getValue();
            if (minute.latest < now && minute.latest >= retainedFrom) { latest = minute.latest; break; }
            for (int i = 0; i < minute.size; i++) {
                long at = entry.getKey() + minute.offsets[i];
                if (at < now && at >= retainedFrom && (latest == null || at > latest)) latest = at;
            }
            if (latest != null) break;
        }
        // Share complete points between periods and publications. Only edge points are recut.
        for (int hours : PERIODS) {
            long from = now - hours * 3_600_000L;
            Counts total = new Counts();
            List<Point> series = new ArrayList<>();
            for (long at = Math.floorDiv(from, MINUTE) * MINUTE; at < now; at += MINUTE) {
                long lower = Math.max(from, at), upper = Math.min(now, at + MINUTE);
                Minute minute = minutes.get(at);
                Counts counts = minute == null ? new Counts() : minute.range(at, Math.max(lower, retainedFrom), upper);
                total.append(counts);
                boolean partial = lower != at || upper != at + MINUTE || retainedFrom > lower;
                Point point;
                if (minute != null && !partial) {
                    if (minute.cached == null) minute.cached = new Point(at, lower, upper, false, false, counts.metrics());
                    point = minute.cached;
                } else point = new Point(at, lower, upper, partial, upper < at + MINUTE, counts.metrics());
                series.add(point);
            }
            reports.put(hours, new Report(from, now, MINUTE, total.metrics(), List.copyOf(series),
                version, initialized ? now : null, latest, retainedFrom));
        }
        return Map.copyOf(reports);
    }
}
