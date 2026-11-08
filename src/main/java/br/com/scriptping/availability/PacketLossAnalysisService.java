package br.com.scriptping.availability;

import br.com.scriptping.Host;
import br.com.scriptping.IpAddress;
import br.com.scriptping.aggregation.MinuteAggregationService;
import br.com.scriptping.aggregation.MinuteAggregationService.Key;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import static br.com.scriptping.availability.AvailabilitySnapshot.*;
import static br.com.scriptping.availability.HostAvailabilityService.*;

/** Background-only builder: one consistent database snapshot for all periods and hosts. */
@Service
public class PacketLossAnalysisService {
    private final MinuteAggregationService store;
    private final PacketLossPolicy policy;
    private final long freshness;

    public PacketLossAnalysisService(MinuteAggregationService store, PacketLossPolicy policy,
        @Value("${monitor.retention-hours:48}") long retentionHours,
        @Value("${indicators.aggregation.interval-ms:60000}") long aggregationInterval) {
        if (retentionHours<1 || aggregationInterval<1) throw new IllegalArgumentException("Invalid aggregation interval or retention");
        policy.validate();
        this.store=store; this.policy=policy; this.freshness=aggregationInterval+120000L;
    }

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public AvailabilitySnapshot build(List<Host> hosts) {
        return buildAt(hosts, AvailabilityDashboard.MINUTES, System.currentTimeMillis());
    }

    AvailabilitySnapshot buildAt(List<Host> hosts, List<Integer> minutes, long now) {
        hosts = hosts.stream().map(h -> new Host(h.id(), h.name(), h.ip(), List.copyOf(h.groups()), h.acknowledgedIcmpProblem())).toList();
        var progress=store.progress();
        long reference=progress.completeThrough()>0 ? progress.completeThrough() : Math.floorDiv(now,60000)*60000;
        var earliest=store.earliestByHost(hosts);
        boolean accelerated=store.historyReady();
        // Live collection health is separate from the selected-period classification.
        var recent=store.totals(reference-5*60_000L, reference, hosts);
        String phase=progress.processedAt()==null ? "STARTING" : progress.cursor()<progress.boundary() ? "CATCHING_UP" : "COMPLETE";
        long retained=earliest.values().stream().mapToLong(Long::longValue).min().orElse(reference);
        var load=new IndicatorLoadStatus(phase, retained, progress.processedAt(), retained, progress.cursor(), progress.boundary());
        var periods=new HashMap<Integer, PeriodData>();
        for (int size : minutes) {
            var option=AvailabilityPeriod.of(size);
            long from=option.from(reference);
            var intervals=option.intervals(from, reference);
            var observations=new HashMap<Key, Observation[]>();
            for (var bucket : store.historicalBuckets(intervals, hosts, accelerated)) {
                var s=bucket.summary();
                var points=observations.computeIfAbsent(s.key(), k -> {
                    var values=new Observation[intervals.size()];
                    Arrays.fill(values, Observation.EMPTY);
                    return values;
                });
                points[bucket.index()]=points[bucket.index()].plus(new Observation(s.healthy(), s.degraded(),
                    s.lost(), s.errors(), s.sum(), s.last(), bucket.validHostMinutes()));
            }
            var data=new HashMap<String, HostPeriod>();
            for (var host : hosts) {
                var key=new Key(host.id(), host.ip());
                var points=observations.get(key);
                if (points==null) { points=new Observation[intervals.size()]; Arrays.fill(points, Observation.EMPTY); }
                var total=Observation.EMPTY;
                for (var point : points) total=total.plus(point);
                var latest=recent.get(key);
                boolean fresh=latest!=null && now-latest.last()<=freshness;
                boolean healthy=fresh && latest.metrics().total()>0;
                data.put(host.id(), new HostPeriod(total, Arrays.asList(points), earliest.getOrDefault(key, reference),
                    status(host, total.metrics()), fresh, healthy));
            }
            periods.put(size, new PeriodData(from, intervals, data));
        }
        return new AvailabilitySnapshot(AvailabilitySnapshot.FORMAT, UUID.randomUUID().toString(), now,
            reference, load, hosts, periods);
    }

    private String status(Host host, Metrics metrics) {
        if (!IpAddress.isValid(host.ip())) return "SEM_IP";
        if (metrics.total()==0) return metrics.errors()>0 ? "FALHA_COLETA" : "SEM_DADOS";
        var quality=policy.windowQuality(metrics.lost(), metrics.total());
        int severity=quality==PacketLossAnalysis.Quality.CRITICO ? 2 : quality==PacketLossAnalysis.Quality.ATENCAO ? 1 : 0;
        if (metrics.latency()!=null) severity=Math.max(severity, metrics.latency()>20 ? 2 : metrics.latency()>=15 ? 1 : 0);
        return severity==2 ? "CRITICO" : severity==1 ? "DEGRADADO" : "NORMAL";
    }

    AvailabilityDashboard.View viewAt(List<Host> hosts, int minutes, long now) {
        return buildAt(hosts, List.of(minutes), now).view(minutes, hosts);
    }
}
