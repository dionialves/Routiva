package br.com.scriptping;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.scheduling.annotation.Scheduled;
import java.time.*;
import java.util.*;
@Repository
@org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization
public class SampleStore {
 // PROVISÓRIO: retenção configurável até a implementação da política definitiva.
 private final Duration retention;
 private final JdbcTemplate jdbc;
 private final org.springframework.context.ApplicationEventPublisher publisher;
 public SampleStore(JdbcTemplate jdbc,long retentionHours,long cleanupSeconds){this(jdbc,retentionHours,cleanupSeconds,event->{});}
 @org.springframework.beans.factory.annotation.Autowired
 public SampleStore(JdbcTemplate jdbc,@org.springframework.beans.factory.annotation.Value("${monitor.retention-hours:48}") long retentionHours,@org.springframework.beans.factory.annotation.Value("${monitor.cleanup-interval-seconds:600}") long cleanupSeconds,org.springframework.context.ApplicationEventPublisher publisher){
  if(cleanupSeconds<1) throw new IllegalArgumentException("monitor.cleanup-interval-seconds deve ser positivo.");
  if(retentionHours<1) throw new IllegalArgumentException("monitor.retention-hours deve ser positivo.");
  this.jdbc=jdbc;this.retention=Duration.ofHours(retentionHours);this.publisher=publisher;
 }
 public PingSample save(Host host,PingProbe.Result result){
  long now=System.currentTimeMillis();
  var key=new org.springframework.jdbc.support.GeneratedKeyHolder();
  jdbc.update(connection->{
   var statement=connection.prepareStatement("INSERT INTO ping_sample(host_id,target,measured_at,latency,error,latency_limit) VALUES(?,?,?,?,?,COALESCE((SELECT latency_limit FROM host_latency_policy WHERE host_id=?),20))",new String[]{"id"});
   statement.setString(1,host.id());statement.setString(2,host.ip());statement.setLong(3,now);statement.setObject(4,result.latency());statement.setString(5,result.error());statement.setString(6,host.id());return statement;
  },key);
  var sample=new PingSample(Objects.requireNonNull(key.getKey()).longValue(),host.id(),host.ip(),now,result.latency(),result.error());
  if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()){
   org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){@Override public void afterCommit(){publisher.publishEvent(sample);}});
  }else publisher.publishEvent(sample);
  return sample;
 }
 @org.springframework.transaction.annotation.Transactional(readOnly=true)
 public void readRecent(long cutoff,java.util.function.Consumer<PingSample> consumer){
  jdbc.query(connection->{var query=connection.prepareStatement("SELECT id,host_id,target,measured_at,latency,error FROM ping_sample WHERE measured_at>=? ORDER BY measured_at,id");query.setLong(1,cutoff);query.setFetchSize(1000);return query;},(org.springframework.jdbc.core.RowCallbackHandler)row->{
   Number latency=(Number)row.getObject("latency");consumer.accept(new PingSample(row.getLong("id"),row.getString("host_id"),row.getString("target"),row.getLong("measured_at"),latency==null?null:latency.doubleValue(),row.getString("error")));
  });
 }
 public record Stats(Double hour,Double minute,Double current,Long measuredAt,String error,long attempts,long failures,long attemptsLastHour,long lossesLastHour,long attemptsLastMinute,long lossesLastMinute) {}
 // PROVISÓRIO: executa ao iniciar e no intervalo configurado; substituir pela futura política de retenção.
 @Scheduled(fixedDelayString="${monitor.cleanup-interval-seconds:600}",timeUnit=java.util.concurrent.TimeUnit.SECONDS)
 public void cleanup(){jdbc.update("DELETE FROM ping_sample WHERE measured_at < ?",System.currentTimeMillis()-retention.toMillis());}
}
