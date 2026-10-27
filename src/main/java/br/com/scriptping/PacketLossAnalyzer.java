package br.com.scriptping;

import static br.com.scriptping.PacketLossAnalysis.*;

/** Pure analysis of completed samples; collection gaps never become packet losses. */
public class PacketLossAnalyzer {

    private final PacketLossPolicy rules;
    private final long interval, maxGap, minuteMs, hourMs;

    public PacketLossAnalyzer(PacketLossPolicy rules, long intervalMs, long timeoutMs) {
        rules.validate();
        if (intervalMs < 1 || timeoutMs < 1) throw new IllegalArgumentException(
            "Intervalo e timeout devem ser positivos."
        );
        this.rules = rules;
        interval = intervalMs;
        maxGap = Math.max(
            (long) (intervalMs * rules.getMaxGapMultiplier()),
            intervalMs + timeoutMs
        );
        minuteMs = rules.getMinuteWindowSeconds() * 1000;
        hourMs = rules.getHourWindowSeconds() * 1000;
    }

    public long lookbackMs() {
        return Math.max(8 * 3600000L, hourMs);
    }

    private class Counts {

        final long start, end;
        long attempts,
            losses,
            successes,
            all,
            previous = -1,
            coverage;
        double sum;

        Counts(long start, long end) {
            this.start = start;
            this.end = end;
        }

        void add(long time, byte outcome, double latency) {
            if (time <= start || time > end) return;
            all++;
            if (outcome == PingSample.ERROR) {
                previous = -1;
                return;
            }
            attempts++;
            if (outcome == PingSample.LOSS) losses++;
            else {
                successes++;
                sum += latency;
            }
            long gap = previous < 0 ? interval : time - previous;
            coverage +=
                gap >= 0 && gap <= maxGap
                    ? Math.min(gap, time - start)
                    : Math.min(interval, time - start);
            previous = time;
        }

        boolean enough() {
            return (
                attempts >= rules.getMinimumSamples() &&
                Math.min(1.0, coverage / (double) (end - start)) >= rules.getMinimumCoverageRatio()
            );
        }

        Double mean() {
            return successes == 0 ? null : sum / successes;
        }
    }

    private Counts counts(RollingPingHistory history, long start, long end) {
        var counts = new Counts(start, end);
        // Histories are ordered, including late results. No database reads are needed.
        int lo = 0,
            hi = history.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (history.time(mid) <= start) lo = mid + 1;
            else hi = mid;
        }
        for (int i = lo; i < history.size() && history.time(i) <= end; i++) counts.add(
            history.time(i),
            history.outcome(i),
            history.latency(i)
        );
        return counts;
    }

    public Quality hourQuality(long losses, long attempts, boolean sufficient) {
        if (!sufficient || attempts == 0) return Quality.DADOS_INSUFICIENTES;
        return rules.windowQuality(losses, attempts);
    }

    public PacketLossAnalysis analyze(RollingPingHistory history, long now) {
        Counts minute = new Counts(now - minuteMs, now),
            hour = new Counts(now - hourMs, now);
        Counts eightHours = counts(history, now - 8 * 3600000L, now),
            latencyHour = counts(history, now - 3600000L, now),
            fiveMinutes = counts(history, now - 300000L, now);
        int firstIndex = 0;
        while (firstIndex < history.size() && history.time(firstIndex) < now - lookbackMs())
            firstIndex++;
        int currentRun = 0,
            minuteRun = 0,
            hourRun = 0,
            maxMinute = 0,
            maxHour = 0;
        for (int i = firstIndex; i < history.size(); i++) {
            long time = history.time(i);
            if (time > now) break;
            byte outcome = history.outcome(i);
            double latency = history.latency(i);
            minute.add(time, outcome, latency);
            hour.add(time, outcome, latency);
            currentRun = outcome == PingSample.LOSS ? currentRun + 1 : 0;
            hourRun = time > now - hourMs && outcome == PingSample.LOSS ? hourRun + 1 : 0;
            minuteRun = time > now - minuteMs && outcome == PingSample.LOSS ? minuteRun + 1 : 0;
            maxMinute = Math.max(maxMinute, minuteRun);
            maxHour = Math.max(maxHour, hourRun);
        }
        PingSample last = history.lastAt(now);
        boolean fresh = last != null && now - last.measuredAt() <= maxGap;
        boolean healthy = fresh && last.outcome() != PingSample.ERROR;
        var stats = new SampleStore.Stats(
            hour.mean(),
            minute.mean(),
            last == null ? null : last.latency(),
            last == null ? null : last.measuredAt(),
            last == null ? null : last.error(),
            hour.all,
            hour.all - hour.successes,
            hour.attempts,
            hour.losses,
            minute.attempts,
            minute.losses,
            eightHours.mean(),
            latencyHour.mean(),
            fiveMinutes.mean(),
            eightHours.attempts,
            eightHours.losses,
            latencyHour.attempts,
            latencyHour.losses,
            fiveMinutes.attempts,
            fiveMinutes.losses
        );
        return new PacketLossAnalysis(
            stats,
            hourQuality(minute.losses, minute.attempts, minute.enough()),
            hourQuality(hour.losses, hour.attempts, hour.enough()),
            currentRun,
            maxMinute,
            maxHour,
            false,
            Level.NORMAL,
            false,
            fresh,
            healthy
        );
    }
}
