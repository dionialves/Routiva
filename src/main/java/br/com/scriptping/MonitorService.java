package br.com.scriptping;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.LoggerFactory;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
@Service
public class MonitorService {
 private final ZabbixClient zabbix; private final PingProbe probe; private final SampleStore store; private final int timeout; private final Semaphore slots;
 private final ExecutorService executor=Executors.newVirtualThreadPerTaskExecutor();
 private final Set<String> running=ConcurrentHashMap.newKeySet();
 private volatile List<Host> hosts=List.of(); private volatile String status="Aguardando sincronização"; private volatile Long lastSync;
 public MonitorService(ZabbixClient zabbix,PingProbe probe,SampleStore store,@Value("${monitor.timeout-ms}") int timeout,@Value("${monitor.concurrency}") int concurrency){this.zabbix=zabbix;this.probe=probe;this.store=store;this.timeout=Math.max(100,timeout);slots=new Semaphore(Math.max(1,concurrency));}
 @Scheduled(fixedDelayString="${zabbix.sync-ms}") public void sync(){
  if(!zabbix.configured()){status="Configure ZABBIX_URL, ZABBIX_USERNAME e ZABBIX_PASSWORD no .env para começar";return;}
  try{hosts=zabbix.fetch();lastSync=System.currentTimeMillis();status="Zabbix conectado";}catch(Exception ex){status="Falha na sincronização com Zabbix; mantendo último inventário";LoggerFactory.getLogger(getClass()).warn("Falha na sincronização Zabbix ({})",ex.getClass().getSimpleName());}
 }
 @Scheduled(fixedDelayString="${monitor.interval-ms}") public void tick(){
  for(Host host:hosts) {
   if(!IpAddress.isValid(host.ip()) || !running.add(host.id()))continue;
   executor.submit(()->{boolean acquired=false;try{slots.acquire();acquired=true;store.save(host,probe.ping(host.ip(),timeout));}catch(InterruptedException ex){Thread.currentThread().interrupt();}catch(Exception ex){LoggerFactory.getLogger(getClass()).error("Falha ao persistir ping do host {}",host.id(),ex);}finally{if(acquired)slots.release();running.remove(host.id());}});
  }
 }
 public List<Host> hosts(){return hosts;} public String status(){return status;} public Long lastSync(){return lastSync;}
 @PreDestroy public void close(){executor.shutdownNow();}
}
