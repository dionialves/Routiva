package br.com.scriptping;

import java.util.*;
import static br.com.scriptping.HostAvailabilityService.*;

/** Minute totals updated on ingestion; compact contributions preserve exact moving-window edges. */
final class AvailabilityHistory {
    static final long MINUTE = 60_000L;
    private final NavigableMap<Long, Minute> minutes = new TreeMap<>();
    private final Map<Long, java.util.function.Supplier<byte[]>> checkpointHours = new HashMap<>();
    private static long hour(long time) { return Math.floorDiv(time, 3_600_000L) * 3_600_000L; }

    List<java.util.function.Supplier<byte[]>> checkpointBlocks() {
        List<java.util.function.Supplier<byte[]>> result = new ArrayList<>();
        for (long at : minutes.keySet().stream().map(AvailabilityHistory::hour).distinct().toList()) {
            result.add(checkpointHours.computeIfAbsent(at, key -> {
                var group = new TreeMap<>(minutes.subMap(at, true, at + 3_600_000L, false));
                group.values().forEach(m -> m.shared = true);
                return new java.util.function.Supplier<byte[]>() {
                    private volatile byte[] bytes;
                    public byte[] get() {
                        if (bytes == null) bytes = CheckpointCodec.encode(out -> {
                            out.writeInt(group.size());
                            for (var entry : group.entrySet()) {
                                out.writeLong(entry.getKey()); Minute m = entry.getValue(); out.writeInt(m.size);
                                for (int i = 0; i < m.size; i++) {
                                    out.writeInt(m.offsets[i]); out.writeDouble(m.latencies[i]); out.writeByte(m.kinds[i]);
                                }
                            }
                        });
                        return bytes;
                    }
                };
            }));
        }
        return result;
    }

    void restoreBlock(byte[] payload) {
        CheckpointCodec.decode(payload, in -> {
            int n = CheckpointCodec.count(in, 60);
            for (int j = 0; j < n; j++) {
                long start = in.readLong(); Minute m = new Minute();
                m.size = CheckpointCodec.count(in, 1_000_000);
                m.offsets = new int[Math.max(64, m.size)];
                m.latencies = new double[m.offsets.length]; m.kinds = new byte[m.offsets.length];
                for (int i = 0; i < m.size; i++) {
                    int offset = m.offsets[i] = in.readInt(); double latency = m.latencies[i] = in.readDouble();
                    byte kind = m.kinds[i] = in.readByte();
                    if (offset < 0 || offset >= MINUTE || kind < 0 || kind > 3) throw new java.io.IOException("Invalid minute");
                    m.latest = Math.max(m.latest, start + offset);
                    switch (kind) {
                        case 0 -> { m.total.h++; m.total.sum += latency; }
                        case 1 -> { m.total.d++; m.total.sum += latency; }
                        case 2 -> m.total.p++;
                        default -> m.total.e++;
                    }
                }
                if (minutes.putIfAbsent(start, m) != null) throw new java.io.IOException("Duplicate minute");
                checkpointHours.put(hour(start), () -> payload);
            }
        });
    }

    private static final class Minute {
        int[] offsets = new int[64];
        double[] latencies = new double[64];
        byte[] kinds = new byte[64]; // healthy, degraded, loss, internal error
        int size;
        boolean shared;
        Minute copy() {
            Minute m = new Minute();
            m.offsets = offsets.clone(); m.latencies = latencies.clone(); m.kinds = kinds.clone();
            m.size = size; m.latest = latest; m.total.append(total); m.cached = cached;
            return m;
        }
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

    AvailabilityHistory fork() {
        var copy = new AvailabilityHistory();
        minutes.forEach((at, minute) -> { minute.shared = true; copy.minutes.put(at, minute); });
        copy.checkpointHours.putAll(checkpointHours);
        return copy;
    }

    void add(PingSample sample) {
        long start = Math.floorDiv(sample.measuredAt(), MINUTE) * MINUTE;
        checkpointHours.remove(hour(start));
        Minute minute = minutes.computeIfAbsent(start, key -> new Minute());
        if (minute.shared) { minute = minute.copy(); minutes.put(start, minute); }
        minute.add(sample, start);
    }

    void expire(long cutoff) {
        var expired = minutes.headMap(Math.floorDiv(cutoff, MINUTE) * MINUTE, false);
        for (long start : expired.keySet()) checkpointHours.remove(hour(start));
        expired.clear();
    }

    boolean isEmpty() { return minutes.isEmpty(); }
    int bucketCount() { return minutes.size(); }

    Map<Integer, Report> reports(long now, long retention, String version, boolean initialized) {
        return reports(now, retention, version, initialized, null);
    }

    Map<Integer, Report> reports(long now, long retention, String version, boolean initialized, IndicatorLoadStatus load) {
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
        for (int hours : java.util.stream.Stream.concat(PERIODS.stream(), java.util.stream.Stream.of(0)).toList()) {
            // Internal key 0 represents the exact five-minute window.
            long from = now - (hours == 0 ? 300_000L : hours * 3_600_000L);
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
                version, initialized ? now : null, latest, retainedFrom, load));
        }
        return Map.copyOf(reports);
    }
}
