package br.com.scriptping;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import static br.com.scriptping.HostAvailabilityService.*;

/** Request-scoped calculations from persisted minute summaries. No history or report cache. */
@Service
public class PacketLossAnalysisService {
    private final MinuteAggregationStore store;
    private final PacketLossPolicy policy;
    private final long retention;
    private final long freshness;
    public PacketLossAnalysisService(MinuteAggregationStore store,PacketLossPolicy policy,
        @Value("${monitor.retention-hours:48}") long retentionHours,
        @Value("${indicators.aggregation.interval-ms:60000}") long aggregationInterval) {
        if(retentionHours<1 || aggregationInterval<1) throw new IllegalArgumentException("Invalid aggregation interval or retention");
        policy.validate();
        this.store=store;this.policy=policy;this.retention=Math.min(48,retentionHours)*3600000L;
        this.freshness=aggregationInterval+120000L;
    }
    private static final Metrics EMPTY=new Counts().metrics();
    private static Metrics metrics(MinuteAggregationStore.Summary s) { return s==null?EMPTY:s.metrics(); }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public AvailabilityDashboard.View view(List<Host> hosts,int minutes) {
        return viewAt(hosts,minutes,System.currentTimeMillis());
    }
    AvailabilityDashboard.View viewAt(List<Host> hosts,int minutes,long now) {
        var progress=store.progress();
        long reference=progress.completeThrough()>0?progress.completeThrough():Math.floorDiv(now,60000)*60000;
        long from=reference-minutes*60000L, retainedFrom=reference-retention;
        String phase=progress.processedAt()==null?"STARTING":progress.cursor()<progress.boundary()?"CATCHING_UP":"COMPLETE";
        var load=new IndicatorLoadStatus(phase,null,progress.processedAt(),retainedFrom,progress.cursor(),progress.boundary());
        String version=progress.cursor()+":"+reference;
        var selected=store.totals(Math.max(from,retainedFrom),reference,hosts);
        Map<Integer,Map<MinuteAggregationStore.Key,MinuteAggregationStore.Summary>> windows=new HashMap<>();
        windows.put(minutes,selected);
        for(int window:List.of(5,60,480)) windows.computeIfAbsent(window,
            n->store.totals(Math.max(reference-n*60000L,retainedFrom),reference,hosts));
        // SQL returns at most one row per minute, regardless of the number of selected hosts.
        var buckets=store.series(Math.max(from,retainedFrom),reference,hosts);
        var series=new ArrayList<Point>();
        var total=new Counts(); Long latest=null;
        for(long at=from;at<reference;at+=60000) {
            var s=buckets.get(at); var m=metrics(s);
            append(total,s);
            if(s!=null) latest=latest==null?s.last():Math.max(latest,s.last());
            series.add(new Point(at,at,at+60000,false,false,m));
        }
        var report=new Report(from,reference,60000,total.metrics(),List.copyOf(series),version,
            progress.processedAt(),latest,retainedFrom,load);
        var rows=new ArrayList<AvailabilityDashboard.Row>();
        for(var host:hosts) {
            var key=new MinuteAggregationStore.Key(host.id(),host.ip());
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
    private static void append(Counts c,MinuteAggregationStore.Summary s) {
        if(s==null)return;
        c.h+=s.healthy();c.d+=s.degraded();c.p+=s.lost();c.e+=s.errors();c.sum+=s.sum();
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Report availability(Host host,int hours) {
        if(!PERIODS.contains(hours))throw new IllegalArgumentException("Período inválido");
        return viewAt(List.of(host),hours*60,System.currentTimeMillis()).report();
    }
}
