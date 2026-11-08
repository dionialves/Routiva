package br.com.scriptping.availability;

import br.com.scriptping.aggregation.MinuteAggregationService;
import br.com.scriptping.aggregation.MinuteAggregationService.Key;
import br.com.scriptping.zabbix.Host;
import java.util.*;
import java.util.function.BiFunction;
import static br.com.scriptping.availability.AvailabilitySnapshot.*;
import static br.com.scriptping.availability.HostAvailabilityService.Metrics;

/** Reuses persisted chart buckets. Only entering/leaving ranges are read from history. */
final class AvailabilityIncrementalUpdate {
    record Range(long from, long to) {}
    static final class InvalidState extends RuntimeException {
        InvalidState() { super("Incremental availability counters are inconsistent"); }
    }

    private final MinuteAggregationService store;
    private final List<Host> hosts;
    private final boolean accelerated;
    private final Map<Range, Map<Key, Observation>> ranges = new HashMap<>();
    private final Map<Key, NavigableMap<Long, Observation>> corrections = new HashMap<>();
    private final BiFunction<Host, Metrics, String> classify;
    private final long freshness;
    private long reused;

    AvailabilityIncrementalUpdate(MinuteAggregationService store, List<Host> hosts,
        List<MinuteAggregationService.Change> changes, BiFunction<Host, Metrics, String> classify, long freshness) {
        this.store=store; this.hosts=hosts; this.classify=classify; this.freshness=freshness;
        accelerated=store.historyReady();
        for (var change : changes) {
            var s=change.delta();
            corrections.computeIfAbsent(s.key(), k -> new TreeMap<>()).merge(s.minute(),
                new Observation(s.healthy(),s.degraded(),s.lost(),s.errors(),s.sum(),s.last(),change.validMinutes()),
                Observation::plus);
        }
    }

    AvailabilitySnapshot apply(AvailabilitySnapshot previous, AvailabilitySnapshot added,
        MinuteAggregationService.Progress progress, long now) {
        long reference=progress.completeThrough();
        var oldHosts=new HashMap<String, Host>();
        previous.hosts().forEach(h -> oldHosts.put(h.id(),h));
        var periods=new HashMap<Integer, PeriodData>();
        var retained=new HashMap<String, Long>();
        var unknown=new ArrayList<Host>();
        for (var host : hosts) {
            var old=oldHosts.get(host.id());
            if (old==null || !Objects.equals(old.ip(),host.ip())) {
                retained.put(host.id(),added.periods().get(5).hosts().get(host.id()).retainedFrom());
            } else {
                long first=previous.periods().get(5).hosts().get(host.id()).retainedFrom();
                if (first>=previous.to()) unknown.add(host);
                else retained.put(host.id(),first);
                var edits=corrections.get(new Key(host.id(),host.ip()));
                if (edits!=null && !edits.isEmpty()) retained.merge(host.id(),edits.firstKey(),Math::min);
            }
        }
        for (int minutes : AvailabilityDashboard.MINUTES) {
            var option=AvailabilityPeriod.of(minutes);
            var intervals=option.intervals(option.from(reference),reference);
            var oldPeriod=previous.periods().get(minutes);
            var rows=new HashMap<String, HostPeriod>();
            for (var host : hosts) {
                var oldHost=oldHosts.get(host.id());
                if (oldHost==null || !Objects.equals(oldHost.ip(),host.ip())) {
                    rows.put(host.id(),added.periods().get(minutes).hosts().get(host.id()));
                    continue;
                }
                var key=new Key(host.id(),host.ip());
                var oldData=oldPeriod.hosts().get(host.id());
                var points=new ArrayList<Observation>();
                for (var interval : intervals) {
                    int oldIndex=overlap(oldPeriod.intervals(),interval);
                    Observation point;
                    if (oldIndex<0) {
                        point=read(interval.from(),interval.to()).getOrDefault(key,Observation.EMPTY);
                    } else {
                        var oldInterval=oldPeriod.intervals().get(oldIndex);
                        point=oldData.points().get(oldIndex);
                        var edits=corrections.get(key);
                        if (edits!=null) for (var delta : edits.subMap(oldInterval.from(),true,oldInterval.to(),false).values())
                            point=point.plus(delta);
                        long a=Math.max(interval.from(),oldInterval.from());
                        long b=Math.min(interval.to(),oldInterval.to());
                        if (oldInterval.from()<a) point=subtract(point,read(oldInterval.from(),a).getOrDefault(key,Observation.EMPTY),a,oldInterval.to());
                        if (b<oldInterval.to()) point=subtract(point,read(b,oldInterval.to()).getOrDefault(key,Observation.EMPTY),a,b);
                        if (interval.from()<a) point=point.plus(read(interval.from(),a).getOrDefault(key,Observation.EMPTY));
                        if (b<interval.to()) point=point.plus(read(b,interval.to()).getOrDefault(key,Observation.EMPTY));
                        reused++;
                    }
                    points.add(point);
                }
                var total=Observation.EMPTY;
                for (var point : points) total=total.plus(point);
                rows.put(host.id(),new HostPeriod(total,points,retained.getOrDefault(host.id(),reference),
                    classify.apply(host,total.metrics()),false,false));
            }
            periods.put(minutes,new PeriodData(option.from(reference),intervals,rows));
        }
        // Resolve first-ever observations, including samples that were in the previous open minute.
        var firstObserved=unknown.stream().filter(h -> periods.get(1051200).hosts().get(h.id()).total().lastSampleAt()!=null).toList();
        var first=store.earliestByHost(firstObserved);
        firstObserved.forEach(h -> retained.put(h.id(),first.getOrDefault(new Key(h.id(),h.ip()),reference)));
        // Collection health is calculated from the updated five-minute cache, never another history query.
        var finished=new HashMap<Integer, PeriodData>();
        for (var entry : periods.entrySet()) {
            var rows=new HashMap<String, HostPeriod>();
            for (var host : hosts) {
                var data=entry.getValue().hosts().get(host.id());
                var recent=periods.get(5).hosts().get(host.id()).total();
                boolean fresh=recent.lastSampleAt()!=null && now-recent.lastSampleAt()<=freshness;
                rows.put(host.id(),new HostPeriod(data.total(),data.points(),retained.getOrDefault(host.id(),reference),
                    classify.apply(host,data.total().metrics()),fresh,fresh && recent.metrics().total()>0));
            }
            finished.put(entry.getKey(),new PeriodData(entry.getValue().from(),entry.getValue().intervals(),rows));
        }
        long earliest=retained.values().stream().mapToLong(Long::longValue).min().orElse(reference);
        var load=new IndicatorLoadStatus(progress.cursor()<progress.boundary()?"CATCHING_UP":"COMPLETE",earliest,
            progress.processedAt(),earliest,progress.cursor(),progress.boundary());
        return new AvailabilitySnapshot(AvailabilitySnapshot.FORMAT,UUID.randomUUID().toString(),now,reference,load,hosts,finished);
    }

    private int overlap(List<AvailabilityPeriod.Interval> intervals, AvailabilityPeriod.Interval wanted) {
        for (int i=0;i<intervals.size();i++) {
            var candidate=intervals.get(i);
            if (candidate.from()<wanted.to() && candidate.to()>wanted.from()) return i;
        }
        return -1;
    }

    private Map<Key, Observation> read(long from,long to) {
        if (from>=to) return Map.of();
        return ranges.computeIfAbsent(new Range(from,to), r -> {
            var observations=new HashMap<Key, Observation>();
            for (var bucket : store.historicalBuckets(List.of(new AvailabilityPeriod.Interval(from,to,false)),hosts,accelerated)) {
                var s=bucket.summary();
                observations.merge(s.key(),new Observation(s.healthy(),s.degraded(),s.lost(),s.errors(),s.sum(),s.last(),bucket.validHostMinutes()),Observation::plus);
            }
            return observations;
        });
    }

    private Observation subtract(Observation a,Observation b,long from,long to) {
        long h=a.healthy()-b.healthy(),d=a.degraded()-b.degraded(),l=a.lost()-b.lost(),e=a.errors()-b.errors();
        long valid=a.validMinutes()-b.validMinutes();
        if (h<0 || d<0 || l<0 || e<0 || valid<0) throw new InvalidState();
        if (h+d+l+e==0) return Observation.EMPTY;
        // Trimming an old prefix cannot remove the newest remaining measurement.
        if (a.lastSampleAt()==null || a.lastSampleAt()<from || a.lastSampleAt()>=to) throw new InvalidState();
        double sum=h+d==0?0:Math.max(0,a.latencySum()-b.latencySum());
        return new Observation(h,d,l,e,sum,a.lastSampleAt(),valid);
    }

    int rangeReads() { return ranges.size(); }
    long reusedPoints() { return reused; }
}
