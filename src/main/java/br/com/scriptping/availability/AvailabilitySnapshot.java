package br.com.scriptping.availability;

import br.com.scriptping.zabbix.Host;
import java.util.*;
import static br.com.scriptping.availability.HostAvailabilityService.*;

/** Immutable, durable generation. Raw sums preserve weighting for every host selection. */
public record AvailabilitySnapshot(int format, String version, long builtAt, long to,
    IndicatorLoadStatus load, List<Host> hosts, Map<Integer, PeriodData> periods) {
    public static final int FORMAT = 1;

    public AvailabilitySnapshot {
        hosts = List.copyOf(hosts);
        periods = Map.copyOf(periods);
    }

    public record Observation(long healthy, long degraded, long lost, long errors,
        double latencySum, Long lastSampleAt, long validMinutes) {
        static final Observation EMPTY = new Observation(0, 0, 0, 0, 0, null, 0);
        Observation plus(Observation b) {
            if (b.lastSampleAt == null) return this;
            if (lastSampleAt == null) return b;
            Long last = Math.max(lastSampleAt, b.lastSampleAt);
            return new Observation(healthy+b.healthy, degraded+b.degraded, lost+b.lost,
                errors+b.errors, latencySum+b.latencySum, last, validMinutes+b.validMinutes);
        }
        Metrics metrics() {
            var c = new Counts();
            c.h=healthy; c.d=degraded; c.p=lost; c.e=errors; c.sum=latencySum;
            return c.metrics();
        }
    }

    public record HostPeriod(Observation total, List<Observation> points, long retainedFrom,
        String status, boolean fresh, boolean collectorHealthy) {
        public HostPeriod { points = List.copyOf(points); }
    }

    public record PeriodData(long from, List<AvailabilityPeriod.Interval> intervals,
        Map<String, HostPeriod> hosts) {
        public PeriodData {
            intervals = List.copyOf(intervals);
            hosts = Map.copyOf(hosts);
        }
    }

    AvailabilityDashboard.View view(int minutes, List<Host> selection) {
        var period = periods.get(minutes);
        if (period == null) throw new IllegalArgumentException("Período inválido");
        var total = Observation.EMPTY;
        var points = new Observation[period.intervals.size()];
        Arrays.fill(points, Observation.EMPTY);
        var rows = new ArrayList<AvailabilityDashboard.Row>();
        long retainedFrom = to;
        for (var host : selection) {
            var data = period.hosts.get(host.id());
            total = total.plus(data.total);
            retainedFrom = Math.min(retainedFrom, data.retainedFrom);
            for (int i=0; i<points.length; i++) points[i] = points[i].plus(data.points.get(i));
            rows.add(new AvailabilityDashboard.Row(host.id(), host.name(), data.total.metrics(),
                host.ip(), host.groups(), data.status, data.fresh, data.collectorHealthy));
        }
        var series = new ArrayList<Point>();
        for (int i=0; i<points.length; i++) {
            var interval = period.intervals.get(i);
            series.add(new Point(interval.from(), interval.from(), interval.to(), interval.partial(), false, points[i].metrics()));
        }
        var option = AvailabilityPeriod.of(minutes);
        long observed = selection.isEmpty() ? 0 : total.validMinutes*60_000L/selection.size();
        var report = new Report(period.from, to, option.bucketMinutes()*60_000L, total.metrics(),
            List.copyOf(series), version+":"+minutes+":"+selection.stream().map(Host::id).sorted().toList(),
            load.evaluatedAt(), total.lastSampleAt, retainedFrom, load, option.grouping(), observed);
        return new AvailabilityDashboard.View(report, List.copyOf(rows));
    }
}
