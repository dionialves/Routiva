package br.com.scriptping;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Value;
import java.time.*;
import java.util.*;
@Repository
public class SampleStore {
 private final JdbcTemplate jdbc; private final int retention;
 public SampleStore(JdbcTemplate jdbc,@Value("${monitor.retention-hours}") int retention){this.jdbc=jdbc; this.retention=Math.max(1,retention);}
 public void save(Host host,PingProbe.Result result){jdbc.update("INSERT INTO ping_sample(host_id,target,measured_at,latency,error) VALUES(?,?,?,?,?)",host.id(),host.ip(),System.currentTimeMillis(),result.latency(),result.error());}
 public record Stats(Double hour,Double minute,Double current,Long measuredAt,String error,long attempts,long failures,long attemptsFiveMinutes,long lossesFiveMinutes) {}
 public Map<String,Stats> stats(List<Host> hosts,long now) {
  Map<String,Stats> result=new HashMap<>();
  for(Host h:hosts) {
   var aggregate=jdbc.queryForMap("""
       SELECT AVG(latency) AS avg_hour,
              AVG(CASE WHEN measured_at>=? THEN latency END) AS avg_minute,
              COUNT(*) AS attempts, COUNT(*)-COUNT(latency) AS failures,
              COUNT(CASE WHEN measured_at>=? AND (latency IS NOT NULL OR error IN ('Tempo esgotado','Sem resposta ICMP')) THEN 1 END) AS attempts_five_minutes,
              COUNT(CASE WHEN measured_at>=? AND latency IS NULL AND error IN ('Tempo esgotado','Sem resposta ICMP') THEN 1 END) AS losses_five_minutes
       FROM ping_sample WHERE host_id=? AND target=? AND measured_at>=? AND measured_at<=?
       """,now-60000,now-300000,now-300000,h.id(),h.ip(),now-3600000,now);
   var last=jdbc.queryForList("SELECT latency,measured_at,error FROM ping_sample WHERE host_id=? AND target=? AND measured_at<=? ORDER BY measured_at DESC,id DESC LIMIT 1",h.id(),h.ip(),now);
   var row=last.isEmpty()?Map.<String,Object>of():last.getFirst();
   result.put(h.id(),new Stats(number(aggregate.get("avg_hour")),number(aggregate.get("avg_minute")),number(row.get("latency")),row.get("measured_at")==null?null:((Number)row.get("measured_at")).longValue(),(String)row.get("error"),((Number)aggregate.get("attempts")).longValue(),((Number)aggregate.get("failures")).longValue(),((Number)aggregate.get("attempts_five_minutes")).longValue(),((Number)aggregate.get("losses_five_minutes")).longValue()));
  } return result;
 }
 private Double number(Object value){return value==null?null:((Number)value).doubleValue();}
 @Scheduled(fixedDelay=3600000) public void cleanup(){jdbc.update("DELETE FROM ping_sample WHERE measured_at < ?",System.currentTimeMillis()-Duration.ofHours(retention).toMillis());}
}
