package br.com.scriptping;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Only grouped rows leave SQL. Summary updates and the cursor commit together. */
@Repository
@org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization
public class MinuteAggregationStore {
    static final long MINUTE = 60_000L;
    record Key(String host, String target) {}
    public record Progress(long cursor, long boundary, Long processedAt, long completeThrough) {}
    public record Batch(long cursor, long boundary, long rows, int summaries) {}
    record Summary(String host, String target, long minute, long total, long healthy,
                   long degraded, long lost, long errors, double sum, long last) {
        Key key() { return new Key(host, target); }
        HostAvailabilityService.Metrics metrics() {
            var c = new HostAvailabilityService.Counts();
            c.h=healthy; c.d=degraded; c.p=lost; c.e=errors; c.sum=sum;
            return c.metrics();
        }
    }
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    public MinuteAggregationStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc=jdbc; this.transaction=new TransactionTemplate(manager);
    }
    public Progress progress() { return progress(false); }
    private Progress progress(boolean lock) {
        return jdbc.queryForObject("SELECT * FROM ping_aggregation_progress WHERE id=1" + (lock ? " FOR UPDATE" : ""),
            (r,n) -> new Progress(r.getLong("cursor_id"),r.getLong("boundary_id"),
                (Long)r.getObject("processed_at"),r.getLong("complete_through")));
    }
    public Batch aggregate(long boundary, int pageSize, long now) {
        if (pageSize<1) throw new IllegalArgumentException("Page size must be positive");
        return transaction.execute(status -> {
            Progress p=progress(true); // Serialize workers, including other application instances.
            // Another worker may already have consumed a newer boundary while we waited.
            if (boundary < p.cursor()) return new Batch(p.cursor(),p.boundary(),0,0);
            long through=jdbc.queryForObject("SELECT COALESCE(MAX(id),?) FROM (SELECT id FROM ping_sample WHERE id>? AND id<=? ORDER BY id LIMIT ?) page",
                Long.class,p.cursor(),p.cursor(),boundary,pageSize);
            // IDs may have gaps after rollback/deletion. A completed empty range is safe to skip.
            if (through==p.cursor()) through=boundary;
            String success="latency IS NOT NULL AND latency>=0 AND latency<=1.7976931348623157E308";
            String loss="(latency IS NULL OR NOT (latency>=0 AND latency<=1.7976931348623157E308)) AND error IN ('Tempo esgotado','Sem resposta ICMP')";
            String bucket="CAST(FLOOR(CAST(measured_at AS NUMERIC(20,0))/60000) AS BIGINT)*60000";
            String sql="SELECT host_id,target,"+bucket+" AS minute_at,COUNT(*) AS total_pings,"
                +"SUM(CASE WHEN "+success+" AND latency<=latency_limit THEN 1 ELSE 0 END) AS healthy,"
                +"SUM(CASE WHEN "+success+" AND latency>latency_limit THEN 1 ELSE 0 END) AS degraded,"
                +"SUM(CASE WHEN "+loss+" THEN 1 ELSE 0 END) AS lost,"
                +"SUM(CASE WHEN "+success+" THEN latency ELSE 0 END) AS latency_sum,MAX(measured_at) AS last_sample_at "
                +"FROM ping_sample WHERE id>? AND id<=? GROUP BY host_id,target,"+bucket;
            var rows=jdbc.query(sql,(r,n)-> {
                long total=r.getLong("total_pings"), h=r.getLong("healthy"),d=r.getLong("degraded"),l=r.getLong("lost");
                return new Summary(r.getString("host_id"),r.getString("target"),r.getLong("minute_at"),total,h,d,l,total-h-d-l,r.getDouble("latency_sum"),r.getLong("last_sample_at"));
            },p.cursor(),through);
            var updates=new ArrayList<Object[]>();
            for (Summary r:rows) updates.add(new Object[] {r.total(),r.healthy(),r.degraded(),r.lost(),r.errors(),r.sum(),r.last(),r.host(),r.target(),r.minute()});
            int[] changed=jdbc.batchUpdate("UPDATE ping_minute SET total_pings=total_pings+?,healthy=healthy+?,degraded=degraded+?,lost=lost+?,errors=errors+?,latency_sum=latency_sum+?,last_sample_at=GREATEST(last_sample_at,?) WHERE host_id=? AND target=? AND minute_at=?",updates);
            var additions=new ArrayList<Object[]>();
            for(int i=0;i<rows.size();i++) {
                if(changed[i]==java.sql.Statement.SUCCESS_NO_INFO)
                    throw new IllegalStateException("Driver must report update counts for summary batches");
                if(changed[i]!=0) continue;
                Summary r=rows.get(i);
                additions.add(new Object[] {r.host(),r.target(),r.minute(),r.total(),r.healthy(),r.degraded(),r.lost(),r.errors(),r.sum(),r.last()});
            }
            jdbc.batchUpdate("INSERT INTO ping_minute(host_id,target,minute_at,total_pings,healthy,degraded,lost,errors,latency_sum,last_sample_at) VALUES(?,?,?,?,?,?,?,?,?,?)",additions);
            long count=rows.stream().mapToLong(Summary::total).sum();
            long complete=through==boundary ? Math.max(p.completeThrough(),Math.floorDiv(now,MINUTE)*MINUTE) : p.completeThrough();
            long processedAt=p.processedAt()==null?now:Math.max(now,p.processedAt());
            jdbc.update("UPDATE ping_aggregation_progress SET cursor_id=?,boundary_id=?,processed_at=?,complete_through=? WHERE id=1",through,boundary,processedAt,complete);
            return new Batch(through,boundary,count,rows.size());
        });
    }
    private static final String SUMS="SUM(total_pings) AS total_pings,SUM(healthy) AS healthy,SUM(degraded) AS degraded,SUM(lost) AS lost,SUM(errors) AS errors,SUM(latency_sum) AS latency_sum,MAX(last_sample_at) AS last_sample_at";
    private Summary read(java.sql.ResultSet r, String host, String target, long minute) throws java.sql.SQLException {
        return new Summary(host,target,minute,r.getLong("total_pings"),r.getLong("healthy"),r.getLong("degraded"),r.getLong("lost"),r.getLong("errors"),r.getDouble("latency_sum"),r.getLong("last_sample_at"));
    }
    Map<Key,Summary> totals(long from,long to,List<Host> hosts) {
        if(hosts.isEmpty()) return Map.of();
        var args=new ArrayList<Object>(List.of(from,to));
        String filter=filter(hosts,args);
        var result=new HashMap<Key,Summary>();
        jdbc.query("SELECT host_id,target,"+SUMS+" FROM ping_minute WHERE minute_at>=? AND minute_at<? AND ("+filter+") GROUP BY host_id,target",
            (org.springframework.jdbc.core.RowCallbackHandler)r->{ var s=read(r,r.getString("host_id"),r.getString("target"),0);result.put(s.key(),s); },args.toArray());
        return result;
    }
    Map<Long,Summary> series(long from,long to,List<Host> hosts) {
        if(hosts.isEmpty()) return Map.of();
        var args=new ArrayList<Object>(List.of(from,to));
        String filter=filter(hosts,args);
        var result=new HashMap<Long,Summary>();
        jdbc.query("SELECT minute_at,"+SUMS+" FROM ping_minute WHERE minute_at>=? AND minute_at<? AND ("+filter+") GROUP BY minute_at ORDER BY minute_at",
            (org.springframework.jdbc.core.RowCallbackHandler)r->{long at=r.getLong("minute_at");result.put(at,read(r,"","",at));},args.toArray());
        return result;
    }
    private String filter(List<Host> hosts,List<Object> args) {
        var clauses=new ArrayList<String>();
        for(var h:hosts) {clauses.add("(host_id=? AND target=?)");args.add(h.id());args.add(h.ip());}
        return String.join(" OR ",clauses);
    }
}
