package br.com.scriptping;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class HostAvailabilityService {
 private final JdbcTemplate jdbc;
 public HostAvailabilityService(JdbcTemplate jdbc){this.jdbc=jdbc;}
 public record Metrics(long healthy,long degraded,long lost,Double technical,Double operational,Double loss,Double degradation,Double latency) {
  public long total(){return healthy+degraded+lost;}
  public String state(){return total()==0?"SEM_DADOS":"COM_DADOS";}
 }
 public record Point(long at,Metrics metrics) {}
 public record Report(long from,long to,long bucketMillis,Metrics total,List<Point> series) {}
 static class Counts {
  long h,d,p;double sum;
  Metrics metrics(){long t=h+d+p;return new Metrics(h,d,p,t==0?null:100d*(h+d)/t,t==0?null:100d*h/t,t==0?null:100d*p/t,t==0?null:100d*d/t,h+d==0?null:sum/(h+d));}
  void add(byte outcome,Double latency,double limit){if(outcome==PingSample.SUCCESS){if(latency<=limit)h++;else d++;sum+=latency;}else if(outcome==PingSample.LOSS)p++;}
 }
 public double limit(String id){return jdbc.query("SELECT latency_limit FROM host_latency_policy WHERE host_id=?",(r,n)->r.getDouble(1),id).stream().findFirst().orElse(20d);}
 public void setLimit(String id,double limit){
  if(!Double.isFinite(limit)||limit<=0||limit>60000)throw new IllegalArgumentException("Limite deve estar entre 0 e 60000 ms.");
  synchronized(this){if(jdbc.update("UPDATE host_latency_policy SET latency_limit=? WHERE host_id=?",limit,id)==0) jdbc.update("INSERT INTO host_latency_policy(host_id,latency_limit) VALUES(?,?)",id,limit);}
 }
 public Report report(Host host,int hours,long now){
  if(!Set.of(1,2,4,8,12,24,48).contains(hours))throw new IllegalArgumentException("Período inválido");
  long from=now-hours*3600000L,bucket=hours<=2?60000L:hours<=12?300000L:900000L;
  Counts total=new Counts();Counts[] bins=new Counts[(int)((now-from)/bucket)];Arrays.setAll(bins,i->new Counts());
  jdbc.query("SELECT id,host_id,target,measured_at,latency,error,latency_limit FROM ping_sample WHERE host_id=? AND target=? AND measured_at>=? AND measured_at<? ORDER BY measured_at",(org.springframework.jdbc.core.RowCallbackHandler)r->{
   Number value=(Number)r.getObject("latency");Double latency=value==null?null:value.doubleValue();
   var sample=new PingSample(r.getLong("id"),host.id(),host.ip(),r.getLong("measured_at"),latency,r.getString("error"));
   double limit=r.getDouble("latency_limit");total.add(sample.outcome(),latency,limit);bins[(int)((sample.measuredAt()-from)/bucket)].add(sample.outcome(),latency,limit);
  },host.id(),host.ip(),from,now);
  List<Point> points=new ArrayList<>();for(int i=0;i<bins.length;i++)points.add(new Point(from+i*bucket,bins[i].metrics()));
  return new Report(from,now,bucket,total.metrics(),points);
 }
}
