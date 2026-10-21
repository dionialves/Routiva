package br.com.scriptping;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.ui.Model;
import org.springframework.beans.factory.annotation.Value;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
@Controller
public class DashboardController {
 private final MonitorService monitor;private final SampleStore store;private final long staleMs;
 public DashboardController(MonitorService monitor,SampleStore store,@Value("${monitor.interval-ms}") long interval,@Value("${monitor.timeout-ms}") long timeout){this.monitor=monitor;this.store=store;staleMs=Math.max(15000,interval*3+timeout);}
 public record Card(String name,String detail,Double hour,Double minute,Double current,String color,String state,String updated) {
  public String format(Double value){return value==null?"—":String.format(Locale.forLanguageTag("pt-BR"),"%.2f",value);}
 }
 public static String color(Double hour,Double minute,Double current,boolean offline){
  if(offline)return "red";
  var values=Arrays.asList(hour,minute,current);
  if(values.stream().anyMatch(v->v!=null && v>20))return "red";
  if(values.stream().anyMatch(v->v!=null && v>=15))return "yellow";
  if(values.stream().anyMatch(Objects::isNull))return "neutral";
  return "green";
 }
 @GetMapping("/") public String index(Model model){populate(model);return "index";}
 @GetMapping("/dashboard") public String fragment(Model model){populate(model);return "dashboard :: content";}
 private void populate(Model model){
  long now=System.currentTimeMillis();var hosts=monitor.hosts();var stats=store.stats(hosts,now);List<Card> cards=new ArrayList<>();Map<String,Host.Group> groups=new TreeMap<>();Map<String,List<SampleStore.Stats>> groupStats=new HashMap<>();
  for(Host host:hosts){var s=stats.get(host.id());boolean stale=s.measuredAt()!=null && now-s.measuredAt()>staleMs;boolean offline=s.measuredAt()!=null && (s.current()==null || stale);
   cards.add(new Card(host.name(),host.ip()+" • "+String.join(", ",host.groups().stream().map(Host.Group::name).toList()),s.hour(),s.minute(),stale?null:s.current(),color(s.hour(),s.minute(),s.current(),offline),s.measuredAt()==null?"Aguardando primeira coleta":stale?"Coleta atrasada":s.error()!=null?s.error():"Respondendo",time(s.measuredAt())));
   for(var g:host.groups()){groups.put(g.id(),g);groupStats.computeIfAbsent(g.id(),k->new ArrayList<>()).add(s);}
  }
  cards.sort(Comparator.comparing(Card::name,String.CASE_INSENSITIVE_ORDER));List<Card> groupCards=new ArrayList<>();
  for(var entry:groups.entrySet()){var list=groupStats.get(entry.getKey());boolean offline=list.stream().anyMatch(s->s.measuredAt()!=null && (s.current()==null || now-s.measuredAt()>staleMs));Double hour=avg(list,0),minute=avg(list,1),current=avg(list.stream().filter(s->s.measuredAt()!=null && now-s.measuredAt()<=staleMs).toList(),2);boolean pending=list.stream().anyMatch(s->s.measuredAt()==null);groupCards.add(new Card(entry.getValue().name(),list.size()+" hosts",hour,minute,current,(pending && !offline && "green".equals(color(hour,minute,current,false))?"neutral":color(hour,minute,current,offline)),offline?"Há hosts sem resposta ou atrasados":pending?"Aguardando coletas":"Hosts respondendo",time(now)));}
  groupCards.sort(Comparator.comparing(Card::name,String.CASE_INSENSITIVE_ORDER));
  model.addAttribute("hosts",cards);model.addAttribute("groups",groupCards);model.addAttribute("status",monitor.status());model.addAttribute("lastSync",time(monitor.lastSync()));model.addAttribute("updated",time(now));
 }
 private static Double avg(List<SampleStore.Stats> list,int metric){return list.stream().map(s->metric==0?s.hour():metric==1?s.minute():s.current()).filter(Objects::nonNull).mapToDouble(Double::doubleValue).average().stream().boxed().findFirst().orElse(null);}
 private static String time(Long millis){return millis==null?"—":DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.of("America/Sao_Paulo")).format(Instant.ofEpochMilli(millis));}
}
