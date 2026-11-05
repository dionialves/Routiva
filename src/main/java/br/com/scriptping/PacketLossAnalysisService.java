package br.com.scriptping;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import br.com.scriptping.aggregation.MinuteAggregationService;
import br.com.scriptping.aggregation.MinuteAggregationService.Key;
import br.com.scriptping.aggregation.MinuteAggregationService.Summary;
import static br.com.scriptping.HostAvailabilityService.*;

/** Request-scoped calculations from persisted minute summaries. No history or report cache. */
@Service
public class PacketLossAnalysisService {
    private final MinuteAggregationService store;
    private final PacketLossPolicy policy;
    private final long freshness;
    public PacketLossAnalysisService(MinuteAggregationService store,PacketLossPolicy policy,
        @Value("${monitor.retention-hours:48}") long retentionHours,
        @Value("${indicators.aggregation.interval-ms:60000}") long aggregationInterval) {
        if(retentionHours<1 || aggregationInterval<1) throw new IllegalArgumentException("Invalid aggregation interval or retention");
        policy.validate();
        this.store=store;this.policy=policy;
        this.freshness=aggregationInterval+120000L;
    }
    private static final Metrics EMPTY=new Counts().metrics();
    private static Metrics metrics(Summary s) { return s==null?EMPTY:s.metrics(); }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public AvailabilityDashboard.View view(List<Host> hosts,int minutes) {
        return viewAt(hosts,minutes,System.currentTimeMillis());
    }
    AvailabilityDashboard.View viewAt(List<Host> hosts,int minutes,long now) {
        var progress=store.progress();
        long reference=progress.completeThrough()>0?progress.completeThrough():Math.floorDiv(now,60000)*60000;
        var period=AvailabilityPeriod.of(minutes);
        long from=period.from(reference), retainedFrom=store.earliest(hosts,reference);
        String phase=progress.processedAt()==null?"STARTING":progress.cursor()<progress.boundary()?"CATCHING_UP":"COMPLETE";
        var load=new IndicatorLoadStatus(phase,null,progress.processedAt(),retainedFrom,progress.cursor(),progress.boundary());
        String version=progress.cursor()+":"+reference+":"+minutes+":"+hosts.stream()
            .map(h->h.id()+"@"+h.ip()).sorted().collect(java.util.stream.Collectors.joining(","));
        var selected=new HashMap<Key,Summary>();
        var series=new ArrayList<Point>();
        var total=new Counts(); Long latest=null;
        long validHostMinutes=0;
        boolean accelerated=store.historyReady();
        for(var interval:period.intervals(from,reference)) {
            var result=store.historicalTotals(interval.from(),interval.to(),hosts,accelerated);
            var bucket=new Counts();
            validHostMinutes+=result.validHostMinutes();
            for(var entry:result.totals().entrySet()) {
                var summary=entry.getValue();
                selected.merge(entry.getKey(),summary,MinuteAggregationService::combine);
                append(bucket,summary);
                latest=latest==null?summary.last():Math.max(latest,summary.last());
            }
            total.append(bucket);
            series.add(new Point(interval.from(),interval.from(),interval.to(),interval.partial(),false,bucket.metrics()));
        }
        Map<Integer,Map<Key,Summary>> windows=new HashMap<>();
        for(int window:List.of(5,60,480)) windows.put(window,
            store.totals(reference-window*60000L,reference,hosts));
        long hostCount=hosts.stream().map(h->new Key(h.id(),h.ip())).distinct().count();
        long observedMillis=hostCount==0?0:validHostMinutes*60000L/hostCount;
        var report=new Report(from,reference,period.bucketMinutes()*60000L,total.metrics(),List.copyOf(series),version,
            progress.processedAt(),latest,retainedFrom,load,period.grouping(),observedMillis);
        var rows=new ArrayList<AvailabilityDashboard.Row>();
        for(var host:hosts) {
            var key=new Key(host.id(),host.ip());
            var s=selected.get(key);
            var recent=windows.get(5).get(key);
            boolean fresh=recent!=null && now-recent.last()<=freshness;
            boolean healthy=fresh && recent.metrics().total()>0;
            String state;
            if(!IpAddress.isValid(host.ip())) state="SEM_IP";
            else if(host.acknowledgedIcmpProblem()) state="RECONHECIDO";
            else if(s==null) state="SEM_DADOS";
            else if(!fresh) state="ATRASADO";
            else if(!healthy) state="FALHA_COLETA";
            else {
                int severity=0;
                for(int window:List.of(5,60,480)) {
                    var m=metrics(windows.get(window).get(key));
                    var quality=policy.windowQuality(m.lost(),m.total());
                    severity=Math.max(severity,quality==PacketLossAnalysis.Quality.CRITICO?2:quality==PacketLossAnalysis.Quality.ATENCAO?1:0);
                    if(m.latency()!=null) severity=Math.max(severity,m.latency()>20?2:m.latency()>=15?1:0);
                }
                state=severity==2?"CRITICO":severity==1?"DEGRADADO":"NORMAL";
            }
            rows.add(new AvailabilityDashboard.Row(host.id(),host.name(),metrics(s),host.ip(),host.groups(),state,fresh,healthy));
        }
        return new AvailabilityDashboard.View(report,List.copyOf(rows));
    }
    private static void append(Counts c,Summary s) {
        if(s==null)return;
        c.h+=s.healthy();c.d+=s.degraded();c.p+=s.lost();c.e+=s.errors();c.sum+=s.sum();
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Report availability(Host host,int hours) {
        if(!PERIODS.contains(hours))throw new IllegalArgumentException("Período inválido");
        return viewAt(List.of(host),hours*60,System.currentTimeMillis()).report();
    }
}
