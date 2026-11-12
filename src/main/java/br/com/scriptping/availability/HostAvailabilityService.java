package br.com.scriptping.availability;

import br.com.scriptping.ingestion.PingSample;

import java.util.*;
import org.springframework.stereotype.Service;

/** Host reports are read exclusively from the published generation. */
@Service
public class HostAvailabilityService {
    static final List<Integer> PERIODS = List.of(1, 8, 24, 48);
    private final AvailabilitySnapshotCache cache;

    public HostAvailabilityService(AvailabilitySnapshotCache cache) {
        this.cache = cache;
    }

    public record Metrics(long healthy, long degraded, long lost, Double technical,
        Double operational, Double loss, Double degradation, Double latency, long errors) {
        public long total() { return healthy + degraded + lost; }
        @com.fasterxml.jackson.annotation.JsonProperty("totalPings")
        public long totalPings() { return total() + errors; }
        public String state() { return total() == 0 ? "SEM_DADOS" : "COM_DADOS"; }
    }

    public record Point(long at, long from, long to, boolean partial, boolean provisional, Metrics metrics) {}
    public record Report(long from, long to, long bucketMillis, Metrics total, List<Point> series,
        String version, Long evaluatedAt, Long lastSampleAt, long retainedFrom, IndicatorLoadStatus load, String grouping, Long observedMillis) {
        public Report(long from, long to, long bucketMillis, Metrics total, List<Point> series,
            String version, Long evaluatedAt, Long lastSampleAt, long retainedFrom, IndicatorLoadStatus load) {
            this(from,to,bucketMillis,total,series,version,evaluatedAt,lastSampleAt,retainedFrom,load,null,null);
        }
        public Report(long from, long to, long bucketMillis, Metrics total, List<Point> series,
            String version, Long evaluatedAt, Long lastSampleAt, long retainedFrom) {
            this(from, to, bucketMillis, total, series, version, evaluatedAt, lastSampleAt, retainedFrom, null);
        }
    }

    public static class Counts {
        public long h, d, p, e;
        public double sum;
        public Metrics metrics() {
            long t = h + d + p;
            return new Metrics(h, d, p, t == 0 ? null : 100d * (h + d) / t,
                t == 0 ? null : 100d * h / t, t == 0 ? null : 100d * p / t,
                t == 0 ? null : 100d * d / t, h + d == 0 ? null : sum / (h + d), e);
        }
        void add(byte outcome, Double latency, double limit) {
            if (outcome == PingSample.SUCCESS) {
                if (latency <= limit) h++; else d++;
                sum += latency;
            } else if (outcome == PingSample.LOSS) p++;
            else e++;
        }
        void append(Counts other) {
            h += other.h; d += other.d; p += other.p; e += other.e; sum += other.sum;
        }
    }

    public Report reportMinutes(String id, int minutes) {
        AvailabilityPeriod.of(minutes);
        return cache.host(id, minutes);
    }

    public Report report(String id, int hours) {
        if (!PERIODS.contains(hours)) throw new IllegalArgumentException("Período inválido");
        return cache.host(id, hours*60);
    }
}
