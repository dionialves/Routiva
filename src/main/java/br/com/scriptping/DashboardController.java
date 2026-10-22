package br.com.scriptping;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.ui.Model;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
@Controller
public class DashboardController {
 private final MonitorService monitor;private final PacketLossAnalysisService analysis;private final PacketLossPolicy lossPolicy;
 public DashboardController(MonitorService monitor,PacketLossAnalysisService analysis,PacketLossPolicy lossPolicy){this.monitor=monitor;this.analysis=analysis;this.lossPolicy=lossPolicy;}
 public record Card(String id,List<String> groupIds,String name,String ip,String groupNames,int members,String detail,Double hour,Double minute,Double current,String color,String state,Long updatedAt,long lossAttempts,long lostPackets,long minuteLossAttempts,long minuteLostPackets,Double lossPercent,Double minuteLossPercent,boolean lossIssue,String diagnostic) {
  public String updated(){return time(updatedAt);}
  public String lossPercentage(){return lossPercent==null?"—":format(lossPercent)+"%";}
  public String minuteLossPercentage(){return minuteLossPercent==null?"—":format(minuteLossPercent)+"%";}
  public String lossTitle(){return lossTitle(lostPackets,lossAttempts,lossPercent);}
  public String minuteLossTitle(){return lossTitle(minuteLostPackets,minuteLossAttempts,minuteLossPercent);}
  private static String lossTitle(long lost,long attempts,Double percent){
   if(attempts==0) return "Sem pings nesta janela";
   return lost+" de "+attempts+" pings";
  }
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
                    @org.springframework.web.bind.annotation.RequestParam(defaultValue="name") String sort,
                    @org.springframework.web.bind.annotation.RequestParam(defaultValue="asc") String direction,
                    @org.springframework.web.bind.annotation.RequestHeader(value="HX-Request",defaultValue="false") boolean partial,
                    Model model) {
  response.setHeader("Vary","HX-Request");
  boolean groupPage=request.getRequestURI().equals("/grupos");
  prepare(model,groupPage,q,pingStatus,groupId,sort,direction);
  return partial?"dashboard :: content":"index";
 }
 // Preserve the former polling URL for clients that already have the page open.
 @GetMapping("/dashboard") public String fragment(Model model){prepare(model,false,"","all","","name","asc");return "dashboard :: content";}

 @SuppressWarnings("unchecked")
 private void prepare(Model model,boolean groupPage,String query,String requestedStatus,String requestedGroupId,String requestedSort,String requestedDirection) {
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
  Set<String> columns=groupPage?Set.of("name","members","hour","minute","current","lossHour","lossMinute","status","updated"):Set.of("name","ip","groups","hour","minute","current","lossHour","lossMinute","status","updated");
  String sort=columns.contains(requestedSort)?requestedSort:"name";
  String direction=requestedDirection.equals("desc")?"desc":"asc";
  model.addAttribute("sort",sort);
  model.addAttribute("direction",direction);
  var filtered=all.stream().filter(card->normalize(card.name()).contains(search))
      .filter(card->matchesStatus(card,selected)).sorted(sortComparator(sort,direction.equals("desc"))).limit(50).toList();
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
 private static <T extends Comparable<? super T>> Comparator<Card> by(java.util.function.Function<Card,T> field,boolean descending) {
  Comparator<T> values=Comparator.naturalOrder();
  if(descending) values=values.reversed();
  return Comparator.comparing(field,Comparator.nullsLast(values));
 }
 private static Comparator<Card> sortComparator(String column,boolean descending) {
  Comparator<Card> comparator=switch(column) {
   case "ip" -> by(c->ipSortKey(c.ip()),descending);
   case "groups" -> by(c->normalize(c.groupNames()),descending);
   case "members" -> by(Card::members,descending);
   case "hour" -> by(Card::hour,descending);
   case "minute" -> by(Card::minute,descending);
   case "current" -> by(Card::current,descending);
   case "lossHour" -> by(Card::lossPercent,descending);
   case "lossMinute" -> by(Card::minuteLossPercent,descending);
   case "status" -> by(c->normalize(c.state()),descending);
   case "updated" -> by(Card::updatedAt,descending);
   default -> by(c->normalize(c.name()),descending);
  };
  return comparator.thenComparing(c->normalize(c.name())).thenComparing(Card::id);
 }
 private static String ipSortKey(String ip) {
  if(!IpAddress.isValid(ip)) return null;
  try {
   byte[] bytes=java.net.InetAddress.getByName(ip).getAddress();
   return String.format(Locale.ROOT,"%02d:",bytes.length)+HexFormat.of().formatHex(bytes);
  } catch(Exception ex) {return null;}
 }
 private static boolean highLatency(Double hour,Double minute,Double current) {
  return Arrays.asList(hour,minute,current).stream().anyMatch(v->v!=null && v>=15);
 }
 private static boolean matchesStatus(Card card,String selected) {
  return switch(selected) {
   case "all" -> true;
   case "normal" -> card.state().startsWith("Normal");
   case "high-latency" -> highLatency(card.hour(),card.minute(),card.current());
   case "packet-loss" -> card.lossIssue();
   case "latency-and-loss" -> card.lossIssue() && highLatency(card.hour(),card.minute(),card.current());
   default -> card.color().equals(selected);
  };
 }
 private record Presentation(String color,String state,boolean lossIssue) {}
 private static Presentation presentation(List<PacketLossAnalysis> results,Double hour,Double minute,Double current) {
  String color=color(hour,minute,current,false);
  boolean high=highLatency(hour,minute,current);
  var level=results.stream().map(PacketLossAnalysis::level).max(Comparator.naturalOrder()).orElse(PacketLossAnalysis.Level.NORMAL);
  var quality=results.stream().map(PacketLossAnalysis::minute).max(Comparator.naturalOrder()).orElse(PacketLossAnalysis.Quality.DADOS_INSUFICIENTES);
  boolean insufficient=results.isEmpty() || results.stream().anyMatch(r->r.minute()==PacketLossAnalysis.Quality.DADOS_INSUFICIENTES);
  boolean collectorProblem=results.stream().anyMatch(r->r.stats().measuredAt()!=null && !r.collectorHealthy());
  boolean issue=results.stream().anyMatch(PacketLossAnalysis::recentIssue);
  String state;
  if(level==PacketLossAnalysis.Level.CRITICO || quality==PacketLossAnalysis.Quality.CRITICO){color="red";state="Crítico: perda de pacotes";}
  else if(collectorProblem){if(!color.equals("red"))color="yellow";state="Coleta interrompida ou com falha";}
  else if(level==PacketLossAnalysis.Level.DEGRADADO){if(!color.equals("red"))color="yellow";state="Degradação recorrente";}
  else if(quality==PacketLossAnalysis.Quality.DEGRADADO || quality==PacketLossAnalysis.Quality.ATENCAO){if(!color.equals("red"))color="yellow";state=quality==PacketLossAnalysis.Quality.DEGRADADO?"Perda recente: degradado":"Atenção: perda de pacotes";}
  else if(high){state="Latência alta";}
  else if(insufficient){color="neutral";state="Dados insuficientes";}
  else if(quality==PacketLossAnalysis.Quality.EVENTO_ISOLADO){state="Evento isolado";}
  else {
   var historical=results.stream().map(PacketLossAnalysis::hour).max(Comparator.naturalOrder()).orElse(PacketLossAnalysis.Quality.DADOS_INSUFICIENTES);
   state=historical==PacketLossAnalysis.Quality.CRITICO?"Normal · histórico crítico":historical==PacketLossAnalysis.Quality.DEGRADADO?"Normal · histórico degradado":historical==PacketLossAnalysis.Quality.ATENCAO?"Normal · histórico com atenção":"Normal";
  }
  if(high && issue)state+=" · latência alta";
  return new Presentation(color,state,issue);
 }
 private void populate(Model model) {
  long now=System.currentTimeMillis();
  var hosts=monitor.hosts();
  var analyses=analysis.analyze(hosts,now);
  List<Card> cards=new ArrayList<>();
  Map<String,Host.Group> groups=new TreeMap<>();
  Map<String,List<PacketLossAnalysis>> groupStats=new HashMap<>();
  Map<String,Integer> groupTotals=new HashMap<>();
  for(Host host:hosts) {
   var quality=analyses.get(host.id());
   var s=quality.stats();
   boolean valid=IpAddress.isValid(host.ip());
   boolean stale=s.measuredAt()!=null && !quality.fresh();
   boolean offline=s.measuredAt()!=null && (s.current()==null || stale);
   Double hour=valid?s.hour():null, minute=valid?s.minute():null, current=valid && !stale?s.current():null;
   var display=presentation(List.of(quality),hour,minute,current);
   String state=host.acknowledgedIcmpProblem()?"Incidente ICMP reconhecido no Zabbix":valid?display.state():"Sem IP válido";
   String diagnostic=host.acknowledgedIcmpProblem()?"Excluído das estatísticas do grupo":!valid?null:quality.diagnostic()+(stale?" · Coleta atrasada":s.error()!=null?" · "+s.error():"");
   if(Objects.equals(diagnostic,state)) diagnostic=null;
   String detail=(valid?host.ip():"Sem IP válido")+" • "+String.join(", ",host.groups().stream().map(Host.Group::name).toList());
   cards.add(new Card(host.id(),host.groups().stream().map(Host.Group::id).toList(),host.name(),valid?host.ip():"—",String.join(", ",host.groups().stream().map(Host.Group::name).toList()),0,detail,
       hour,minute,current,valid && !host.acknowledgedIcmpProblem()?display.color():"gray",state,valid?s.measuredAt():null,
       valid?s.attemptsLastHour():0,valid?s.lossesLastHour():0,valid?s.attemptsLastMinute():0,valid?s.lossesLastMinute():0,valid?lossPolicy.visiblePercentage(s.lossesLastHour(),s.attemptsLastHour()):null,valid?lossPolicy.visiblePercentage(s.lossesLastMinute(),s.attemptsLastMinute()):null,valid && !host.acknowledgedIcmpProblem() && display.lossIssue(),diagnostic));
   for(var g:host.groups()) {
    groups.put(g.id(),g);
    groupTotals.merge(g.id(),1,Integer::sum);
    var list=groupStats.computeIfAbsent(g.id(),k->new ArrayList<>());
    if(valid && !host.acknowledgedIcmpProblem()) list.add(quality);
   }
  }
  cards.sort(Comparator.comparing(Card::name,String.CASE_INSENSITIVE_ORDER));
  List<Card> groupCards=new ArrayList<>();
  for(var entry:groups.entrySet()) {
   var qualities=groupStats.get(entry.getKey());
   var list=qualities.stream().map(PacketLossAnalysis::stats).toList();
   int total=groupTotals.get(entry.getKey()), excluded=total-list.size();
   Double hour=avg(list,0),minute=avg(list,1),current=avg(qualities.stream().filter(PacketLossAnalysis::fresh).map(PacketLossAnalysis::stats).toList(),2);
   long attempts=list.stream().mapToLong(SampleStore.Stats::attemptsLastHour).sum();
   long lost=list.stream().mapToLong(SampleStore.Stats::lossesLastHour).sum();
   long minuteAttempts=list.stream().mapToLong(SampleStore.Stats::attemptsLastMinute).sum();
   long minuteLost=list.stream().mapToLong(SampleStore.Stats::lossesLastMinute).sum();
   var display=presentation(qualities,hour,minute,current);
   String groupColor=list.isEmpty()?"gray":display.color();
   String state=list.isEmpty()?"Sem hosts elegíveis para estatísticas":display.state();
   var worstMinute=qualities.stream().map(PacketLossAnalysis::minute).max(Comparator.naturalOrder()).orElse(PacketLossAnalysis.Quality.DADOS_INSUFICIENTES);
   var worstHour=qualities.stream().map(PacketLossAnalysis::hour).max(Comparator.naturalOrder()).orElse(PacketLossAnalysis.Quality.DADOS_INSUFICIENTES);
   String diagnostic=list.isEmpty()?null:"Pior membro · 1 min: "+worstMinute+" · 1 h: "+worstHour;
   long recognized=hosts.stream().filter(h->h.acknowledgedIcmpProblem() && IpAddress.isValid(h.ip()) && h.groups().stream().anyMatch(g->g.id().equals(entry.getKey()))).count();
   long invalid=excluded-recognized;
   String detail=total+" hosts"+(invalid>0?" · "+invalid+" sem IP válido":"")+(recognized>0?" · "+recognized+" com incidente reconhecido (excluídos)":"");
   groupCards.add(new Card(entry.getKey(),List.of(),entry.getValue().name(),"","",total,detail,hour,minute,current,groupColor,state,now,attempts,lost,minuteAttempts,minuteLost,lossPolicy.visiblePercentage(lost,attempts),lossPolicy.visiblePercentage(minuteLost,minuteAttempts),display.lossIssue(),diagnostic));
  }
  groupCards.sort(Comparator.comparing(Card::name,String.CASE_INSENSITIVE_ORDER));
  model.addAttribute("hosts",cards);model.addAttribute("groups",groupCards);model.addAttribute("status",monitor.status());model.addAttribute("lastSync",time(monitor.lastSync()));model.addAttribute("updated",time(now));
 }
 private static Double avg(List<SampleStore.Stats> list,int metric){return list.stream().map(s->metric==0?s.hour():metric==1?s.minute():s.current()).filter(Objects::nonNull).mapToDouble(Double::doubleValue).average().stream().boxed().findFirst().orElse(null);}
 private static String time(Long millis){return millis==null?"—":DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.of("America/Sao_Paulo")).format(Instant.ofEpochMilli(millis));}
}
