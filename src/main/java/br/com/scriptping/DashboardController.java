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
 public record Card(String id,List<String> groupIds,String name,String detail,Double hour,Double minute,Double current,String color,String state,String updated,long lossAttempts,long lostPackets,String diagnostic) {
  public String lossPercentage(){return lossAttempts==0?"—":format(100.0*lostPackets/lossAttempts)+"%";}
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
 @GetMapping({"/", "/hosts", "/grupos"})
 public String page(jakarta.servlet.http.HttpServletRequest request,
                    jakarta.servlet.http.HttpServletResponse response,
                    @org.springframework.web.bind.annotation.RequestParam(defaultValue="") String q,
                    @org.springframework.web.bind.annotation.RequestParam(defaultValue="all") String pingStatus,
                    @org.springframework.web.bind.annotation.RequestParam(defaultValue="") String groupId,
                    @org.springframework.web.bind.annotation.RequestHeader(value="HX-Request",defaultValue="false") boolean partial,
                    Model model) {
  response.setHeader("Vary","HX-Request");
  boolean groupPage=request.getRequestURI().equals("/grupos");
  prepare(model,groupPage,q,pingStatus,groupId);
  return partial?"dashboard :: content":"index";
 }
 // Preserve the former polling URL for clients that already have the page open.
 @GetMapping("/dashboard") public String fragment(Model model){prepare(model,false,"","all","");return "dashboard :: content";}

 @SuppressWarnings("unchecked")
 private void prepare(Model model,boolean groupPage,String query,String requestedStatus,String requestedGroupId) {
  populate(model);
  String selected=Set.of("all","green","yellow","red","neutral","gray","normal","high-latency","packet-loss","latency-and-loss").contains(requestedStatus)?requestedStatus:"all";
  String groupId=groupPage?"":requestedGroupId.strip();
  var groupOptions=(List<Card>)model.getAttribute("groups");
  var selectedGroup=groupOptions.stream().filter(card->card.id().equals(groupId)).findFirst();
  var all=((List<Card>)model.getAttribute(groupPage?"groups":"hosts")).stream()
      .filter(card->groupId.isEmpty() || card.groupIds().contains(groupId)).toList();
  model.addAttribute("groupId",groupId);
  model.addAttribute("groupOptions",groupOptions);
  model.addAttribute("groupUnavailable",!groupId.isEmpty() && selectedGroup.isEmpty());
  model.addAttribute("selectedGroupName",groupId.isEmpty()?"":selectedGroup.map(Card::name).orElse("Grupo indisponível"));
  String search=normalize(query.strip());
  var filtered=all.stream().filter(card->normalize(card.name()).contains(search))
      .filter(card->matchesStatus(card,selected)).toList();
  model.addAttribute("cards",filtered);
  model.addAttribute("total",all.size());
  model.addAttribute("groupPage",groupPage);
  model.addAttribute("pageTitle",groupPage?"Grupos de hosts":"Hosts");
  model.addAttribute("pagePath",groupPage?"/grupos":"/hosts");
  model.addAttribute("q",query);
  model.addAttribute("pingStatus",selected);
 }
 private static String normalize(String value) {
  return java.text.Normalizer.normalize(value,java.text.Normalizer.Form.NFD)
      .replaceAll("\\p{M}","").toLowerCase(Locale.ROOT);
 }
 private static boolean highLatency(Double hour,Double minute,Double current) {
  return Arrays.asList(hour,minute,current).stream().anyMatch(v->v!=null && v>=15);
 }
 private static boolean matchesStatus(Card card,String selected) {
  return switch(selected) {
   case "all" -> true;
   case "normal" -> card.state().equals("Normal");
   case "high-latency" -> highLatency(card.hour(),card.minute(),card.current());
   case "packet-loss" -> card.lostPackets()>0;
   case "latency-and-loss" -> card.lostPackets()>0 && highLatency(card.hour(),card.minute(),card.current());
   default -> card.color().equals(selected);
  };
 }
 private static String healthState(Double hour,Double minute,Double current,boolean loss,boolean offline,boolean pending,boolean stale) {
  boolean high=highLatency(hour,minute,current);
  if(loss) return high?"Com latência e perda de pacotes":"Com perda de pacotes";
  if(stale) return "Coleta atrasada";
  if(offline) return "Falha na coleta";
  if(high) return "Latência alta";
  if(pending) return "Aguardando dados";
  return "Normal";
 }
 private void populate(Model model) {
  long now=System.currentTimeMillis();
  var hosts=monitor.hosts();
  var stats=store.stats(hosts,now);
  List<Card> cards=new ArrayList<>();
  Map<String,Host.Group> groups=new TreeMap<>();
  Map<String,List<SampleStore.Stats>> groupStats=new HashMap<>();
  Map<String,Integer> groupTotals=new HashMap<>();
  for(Host host:hosts) {
   var s=stats.get(host.id());
   boolean valid=IpAddress.isValid(host.ip());
   boolean stale=s.measuredAt()!=null && now-s.measuredAt()>staleMs;
   boolean offline=s.measuredAt()!=null && (s.current()==null || stale);
   boolean loss=valid && s.lossesLastHour()>0;
   Double hour=valid?s.hour():null, minute=valid?s.minute():null, current=valid && !stale?s.current():null;
   String state=valid?healthState(hour,minute,current,loss,offline,s.measuredAt()==null,stale):"Sem IP válido";
   String diagnostic=!valid?null:stale?"Coleta atrasada":s.error();
   if(Objects.equals(diagnostic,state)) diagnostic=null;
   String detail=(valid?host.ip():"Sem IP válido")+" • "+String.join(", ",host.groups().stream().map(Host.Group::name).toList());
   cards.add(new Card(host.id(),host.groups().stream().map(Host.Group::id).toList(),host.name(),detail,
       hour,minute,current,valid?color(hour,minute,current,offline || loss):"gray",state,valid?time(s.measuredAt()):"—",
       valid?s.attemptsLastHour():0,valid?s.lossesLastHour():0,diagnostic));
   for(var g:host.groups()) {
    groups.put(g.id(),g);
    groupTotals.merge(g.id(),1,Integer::sum);
    var list=groupStats.computeIfAbsent(g.id(),k->new ArrayList<>());
    if(valid) list.add(s);
   }
  }
  cards.sort(Comparator.comparing(Card::name,String.CASE_INSENSITIVE_ORDER));
  List<Card> groupCards=new ArrayList<>();
  for(var entry:groups.entrySet()) {
   var list=groupStats.get(entry.getKey());
   int total=groupTotals.get(entry.getKey()), excluded=total-list.size();
   boolean stale=list.stream().anyMatch(s->s.measuredAt()!=null && now-s.measuredAt()>staleMs);
   boolean offline=list.stream().anyMatch(s->s.measuredAt()!=null && (s.current()==null || now-s.measuredAt()>staleMs));
   Double hour=avg(list,0),minute=avg(list,1),current=avg(list.stream().filter(s->s.measuredAt()!=null && now-s.measuredAt()<=staleMs).toList(),2);
   long attempts=list.stream().mapToLong(SampleStore.Stats::attemptsLastHour).sum();
   long lost=list.stream().mapToLong(SampleStore.Stats::lossesLastHour).sum();
   boolean pending=list.stream().anyMatch(s->s.measuredAt()==null);
   String groupColor=color(hour,minute,current,offline || lost>0);
   if(list.isEmpty()) groupColor="gray";
   else if(pending && !offline && groupColor.equals("green")) groupColor="neutral";
   String state=list.isEmpty()?"Sem hosts com IP válido":healthState(hour,minute,current,lost>0,offline,pending,stale);
   String diagnostic=offline?"Há hosts sem resposta, com falha ou coleta atrasada":null;
   String detail=total+" hosts"+(excluded>0?" · "+excluded+" sem IP válido":"");
   groupCards.add(new Card(entry.getKey(),List.of(),entry.getValue().name(),detail,hour,minute,current,groupColor,state,time(now),attempts,lost,diagnostic));
  }
  groupCards.sort(Comparator.comparing(Card::name,String.CASE_INSENSITIVE_ORDER));
  model.addAttribute("hosts",cards);model.addAttribute("groups",groupCards);model.addAttribute("status",monitor.status());model.addAttribute("lastSync",time(monitor.lastSync()));model.addAttribute("updated",time(now));
 }
 private static Double avg(List<SampleStore.Stats> list,int metric){return list.stream().map(s->metric==0?s.hour():metric==1?s.minute():s.current()).filter(Objects::nonNull).mapToDouble(Double::doubleValue).average().stream().boxed().findFirst().orElse(null);}
 private static String time(Long millis){return millis==null?"—":DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.of("America/Sao_Paulo")).format(Instant.ofEpochMilli(millis));}
}
