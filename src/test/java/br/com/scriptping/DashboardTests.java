package br.com.scriptping;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:test;DB_CLOSE_DELAY=-1", "zabbix.url=", "zabbix.username=", "zabbix.password=", "spring.datasource.username=sa", "spring.datasource.password=", "monitor.loss.minimum-coverage-ratio=0", "monitor.loss.minimum-samples=1"})
@AutoConfigureMockMvc
class DashboardTests {
 @Test void rendersHostDetailsAndValidatesPeriods() throws Exception {
  Host host=new Host("details", "Host detalhado", "127.0.0.1", java.util.List.of(new Host.Group("g1","Produção")));
  org.mockito.Mockito.when(monitor.hosts()).thenReturn(java.util.List.of(host));
  store.save(host,new PingProbe.Result(10d,null));
  for(int hours:new int[]{1,2,4,8,12,24,48}) mvc.perform(get("/hosts/details").param("hours",String.valueOf(hours)))
    .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Host detalhado")))
    .andExpect(content().string(org.hamcrest.Matchers.containsString("window.hostReport")));
  mvc.perform(get("/api/hosts/details/availability")).andExpect(status().isOk()).andExpect(jsonPath("$.total.operational").value(100));
  mvc.perform(get("/hosts/details").param("hours","3")).andExpect(status().isBadRequest());
  mvc.perform(get("/hosts/missing")).andExpect(status().isNotFound());
 }

 @Autowired MockMvc mvc;
 @Autowired SampleStore store;
 @Autowired PacketLossAnalysisService analysis;
 @org.springframework.test.context.bean.override.mockito.MockitoBean MonitorService monitor;
 @Test void paginatesAfterFilteringAndSorting() throws Exception {
  var hosts=java.util.stream.IntStream.rangeClosed(1,45).mapToObj(i->new Host("page-"+i,String.format("Host %02d",i),"10.0.0."+i,java.util.List.of(new Host.Group("group-"+i,String.format("Grupo %02d",i))))).toList();
  org.mockito.Mockito.when(monitor.hosts()).thenReturn(hosts);
  for(String path:java.util.List.of("/hosts","/grupos")) {
   mvc.perform(get(path)).andExpect(status().isOk()).andExpect(cardCount(20))
       .andExpect(model().attribute("totalPages",3));
   mvc.perform(get(path).header("HX-Request","true").param("page","2"))
       .andExpect(status().isOk()).andExpect(cardCount(20))
       .andExpect(result->assertThat(resultCards(result).getFirst().name()).endsWith("21"))
       .andExpect(content().string(org.hamcrest.Matchers.containsString("Página 2 de 3")));
   mvc.perform(get(path).param("page","999"))
       .andExpect(status().isOk()).andExpect(cardCount(5)).andExpect(model().attribute("currentPage",3));
   mvc.perform(get(path).param("page","-1"))
       .andExpect(status().isOk()).andExpect(cardCount(20)).andExpect(model().attribute("currentPage",1));
   mvc.perform(get(path).param("page","2").param("direction","desc"))
       .andExpect(status().isOk()).andExpect(result->assertThat(resultCards(result).getFirst().name()).endsWith("25"));
   mvc.perform(get(path).param("page","3").param("q","45"))
       .andExpect(status().isOk()).andExpect(cardCount(1)).andExpect(model().attribute("currentPage",1));
   mvc.perform(get(path).param("page","3").param("q","inexistente"))
       .andExpect(status().isOk()).andExpect(cardCount(0)).andExpect(model().attribute("currentPage",1));
  }
 }
 @Test void thresholds(){
  assertThat(DashboardController.color(14.99,1d,2d,false)).isEqualTo("green");
  assertThat(DashboardController.color(15d,1d,2d,false)).isEqualTo("yellow");
  assertThat(DashboardController.color(1d,20d,2d,false)).isEqualTo("yellow");
  assertThat(DashboardController.color(1d,2d,20.01,false)).isEqualTo("red");
  assertThat(DashboardController.color(30d,17d,1d,false)).isEqualTo("red");
  assertThat(DashboardController.color(1d,2d,null,true)).isEqualTo("red");
  assertThat(DashboardController.color(null,null,null,false)).isEqualTo("neutral");
 }
 @Test void rendersDashboardAndFragment() throws Exception {
  org.mockito.Mockito.when(monitor.hosts()).thenReturn(java.util.List.of());
  mvc.perform(get("/")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("hx-trigger=\"refresh\"")));
  mvc.perform(get("/dashboard")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Nenhum host")));
  mvc.perform(get("/webjars/htmx.org/2.0.4/dist/htmx.min.js")).andExpect(status().isOk());
  mvc.perform(get("/dashboard.js")).andExpect(status().isOk());
  for(String path:java.util.List.of("/hosts","/grupos")) {
   mvc.perform(get(path)).andExpect(status().isOk())
       .andExpect(content().string(org.hamcrest.Matchers.containsString("Atualizar agora")))
       .andExpect(content().string(org.hamcrest.Matchers.containsString("60 segundos")))
       .andExpect(content().string(org.hamcrest.Matchers.containsString("hx-include=\"#filters\"")));
  }
 }
 @Test void rendersPopulatedCards() throws Exception {
  Host h=new Host("render", "Servidor exemplo", "127.0.0.1", java.util.List.of(new Host.Group("g1","Produção")));
  store.save(h,new PingProbe.Result(16d,null));
  org.mockito.Mockito.when(monitor.hosts()).thenReturn(java.util.List.of(h));
  mvc.perform(get("/dashboard")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Servidor exemplo"))).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("host-groups")))).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Última coleta")))).andExpect(content().string(org.hamcrest.Matchers.containsString("16,00"))).andExpect(content().string(org.hamcrest.Matchers.containsString("host-row yellow")));
 }

 @Test void dedicatedPagesAndCombinedFiltersStayAppliedDuringPolling() throws Exception {
  Host green=new Host("filter-green","Servidor Ágil","10.0.0.1",java.util.List.of(new Host.Group("filter-g","Grupo Ágil")));
  Host yellow=new Host("filter-yellow","Servidor Médio","10.0.0.2",java.util.List.of(new Host.Group("filter-y","Grupo Médio")));
  Host red=new Host("filter-red","Servidor Lento","10.0.0.3",java.util.List.of(new Host.Group("filter-r","Grupo Lento")));
  Host neutral=new Host("filter-neutral","Servidor Novo","10.0.0.4",java.util.List.of(new Host.Group("filter-n","Grupo Novo")));
  store.save(green,new PingProbe.Result(10d,null));
  store.save(yellow,new PingProbe.Result(17d,null));
  store.save(red,new PingProbe.Result(25d,null));
  org.mockito.Mockito.when(monitor.hosts()).thenReturn(java.util.List.of(green,yellow,red,neutral));
  for(String path:java.util.List.of("/hosts","/grupos")) {
   String prefix=path.equals("/hosts")?"Servidor":"Grupo";
   mvc.perform(get(path)).andExpect(status().isOk())
       .andExpect(content().string(org.hamcrest.Matchers.containsString("hx-include=\"#filters\"")))
       .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"pingStatus\"")))
       .andExpect(content().string(org.hamcrest.Matchers.containsString("<table class=\"hosts-table")))
       .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("<article"))));
   for(var pair:java.util.Map.of("green","Ágil","yellow","Médio","red","Lento","neutral","Novo").entrySet()) {
    mvc.perform(get(path).header("HX-Request","true").param("pingStatus",pair.getKey()))
       .andExpect(status().isOk())
       .andExpect(content().string(org.hamcrest.Matchers.containsString(prefix+" "+pair.getValue())))
       .andExpect(content().string(org.hamcrest.Matchers.containsString("1 de 4")))
       .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("<form"))));
   }
   mvc.perform(get(path).header("HX-Request","true").param("q","AGIL").param("pingStatus","green"))
       .andExpect(status().isOk())
       .andExpect(content().string(org.hamcrest.Matchers.containsString(prefix+" Ágil")))
       .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(prefix+" Médio"))));
   mvc.perform(get(path).header("HX-Request","true").param("q","agil").param("pingStatus","red"))
       .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Nenhum resultado")));
  }
  store.save(green,new PingProbe.Result(null,"Tempo esgotado"));
  mvc.perform(get("/hosts").header("HX-Request","true").param("q","agil").param("pingStatus","green"))
      .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Nenhum resultado")));
  mvc.perform(get("/grupos").header("HX-Request","true").param("q","agil").param("pingStatus","red"))
      .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Grupo Ágil")));
 }

 @Test void nameFilterDoesNotExcludeMembersFromGroupAverages() throws Exception {
  var group=new Host.Group("average-group","Grupo Principal");
  var a=new Host("average-a","Outro nome","10.1.1.1",java.util.List.of(group));
  var b=new Host("average-b","Mais um host","10.1.1.2",java.util.List.of(group));
  store.save(a,new PingProbe.Result(10d,null));store.save(b,new PingProbe.Result(16d,null));
  org.mockito.Mockito.when(monitor.hosts()).thenReturn(java.util.List.of(a,b));
  mvc.perform(get("/grupos").header("HX-Request","true").param("q","principal"))
      .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("13,00")))
      .andExpect(content().string(org.hamcrest.Matchers.containsString("2 hosts")))
      .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Outro nome"))));
 }

 @Test void groupLinksOpenOnlyMembersAndPreserveGroupDuringFiltering() throws Exception {
  var a=new Host.Group("drill-a","Grupo Repetido");
  var b=new Host.Group("drill-b","Grupo Repetido");
  var first=new Host("drill-first","Host Exclusivo A","10.2.0.1",java.util.List.of(a));
  var shared=new Host("drill-shared","Host Compartilhado","10.2.0.2",java.util.List.of(a,b));
  var outside=new Host("drill-outside","Host Exclusivo B","10.2.0.3",java.util.List.of(b));
  store.save(first,new PingProbe.Result(10d,null));
  store.save(shared,new PingProbe.Result(25d,null));
  store.save(outside,new PingProbe.Result(10d,null));
  org.mockito.Mockito.when(monitor.hosts()).thenReturn(java.util.List.of(first,shared,outside));
  mvc.perform(get("/grupos"))
      .andExpect(status().isOk())
      .andExpect(content().string(org.hamcrest.Matchers.containsString("href=\"/hosts?groupId=drill-a\"")))
      .andExpect(content().string(org.hamcrest.Matchers.containsString("href=\"/hosts?groupId=drill-b\"")));
  mvc.perform(get("/hosts").param("groupId","drill-a"))
      .andExpect(status().isOk())
      .andExpect(selectedGroup("drill-a"))
      .andExpect(cardCount(2))
      .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Host Exclusivo B"))));
  for(String groupId:java.util.List.of("drill-a","drill-b")) {
   mvc.perform(get("/hosts").header("HX-Request","true").param("groupId",groupId)
           .param("q","compartilhado").param("pingStatus","red"))
       .andExpect(status().isOk()).andExpect(cardCount(1))
       .andExpect(content().string(org.hamcrest.Matchers.containsString("Host Compartilhado")));
  }
  mvc.perform(get("/hosts").header("HX-Request","true").param("groupId","drill-a").param("q","Exclusivo B"))
      .andExpect(status().isOk()).andExpect(cardCount(0));
  mvc.perform(get("/hosts"))
      .andExpect(status().isOk()).andExpect(cardCount(3));
  mvc.perform(get("/hosts").param("groupId","unknown"))
      .andExpect(status().isOk()).andExpect(cardCount(0))
      .andExpect(selectedGroup("unknown"));
  org.mockito.Mockito.when(monitor.hosts()).thenReturn(java.util.List.of(outside));
  mvc.perform(get("/hosts").header("HX-Request","true").param("groupId","drill-a"))
      .andExpect(status().isOk()).andExpect(cardCount(0))
      .andExpect(content().string(org.hamcrest.Matchers.containsString("Grupo indisponível")));
 }

 @Test void hostsWithoutValidIpAreGrayAndDoNotAffectGroups() throws Exception {
  var healthyGroup=new Host.Group("ip-healthy","Grupo Saudável");
  var invalidGroup=new Host.Group("ip-invalid","Grupo Sem IP");
  var failingGroup=new Host.Group("ip-failing","Grupo Indisponível");
  var pendingGroup=new Host.Group("ip-pending","Grupo Aguardando");
  var healthy=new Host("ip-healthy-host","Host Válido","10.3.0.1",java.util.List.of(healthyGroup));
  var missing=new Host("ip-missing-host","Host Sem IP","",java.util.List.of(healthyGroup,invalidGroup));
  var malformed=new Host("ip-malformed-host","Host IP Inválido","999.1.1.1",java.util.List.of(healthyGroup,invalidGroup));
  var noSamples=new Host("ip-no-samples","Host Sem Coleta","",java.util.List.of(healthyGroup,invalidGroup));
  var failed=new Host("ip-failed-host","Host Sem Resposta","10.3.0.2",java.util.List.of(failingGroup));
  var pending=new Host("ip-pending-host","Host Aguardando","10.3.0.3",java.util.List.of(pendingGroup));
  store.save(healthy,new PingProbe.Result(10d,null));
  store.save(missing,new PingProbe.Result(null,"IP ausente ou inválido"));
  store.save(malformed,new PingProbe.Result(99d,null));
  store.save(failed,new PingProbe.Result(null,"Tempo esgotado"));
  org.mockito.Mockito.when(monitor.hosts()).thenReturn(java.util.List.of(healthy,missing,malformed,noSamples,failed,pending));
  mvc.perform(get("/hosts").param("pingStatus","gray"))
      .andExpect(status().isOk()).andExpect(cardCount(3))
      .andExpect(content().string(org.hamcrest.Matchers.containsString("Cinza · sem IP válido")))
      .andExpect(result -> {
       for(var card:resultCards(result)) {
        assertThat(card.color()).isEqualTo("gray");
        assertThat(card.hour()).isNull();assertThat(card.minute()).isNull();assertThat(card.current()).isNull();
        assertThat(card.state()).isEqualTo("Sem IP válido");
       }
      });
  mvc.perform(get("/grupos").header("HX-Request","true").param("pingStatus","green"))
      .andExpect(status().isOk()).andExpect(cardCount(1))
      .andExpect(result -> {
       var card=resultCards(result).getFirst();
       assertThat(card.id()).isEqualTo("ip-healthy");
       assertThat(card.hour()).isEqualTo(10d);assertThat(card.minute()).isEqualTo(10d);assertThat(card.current()).isEqualTo(10d);
       assertThat(card.detail()).isEqualTo("4 hosts · 3 sem IP válido");
      });
  mvc.perform(get("/grupos").header("HX-Request","true").param("pingStatus","gray"))
      .andExpect(status().isOk()).andExpect(cardCount(1))
      .andExpect(result -> assertThat(resultCards(result).getFirst().id()).isEqualTo("ip-invalid"));
  mvc.perform(get("/hosts").header("HX-Request","true").param("groupId","ip-healthy").param("q","inválido").param("pingStatus","gray"))
      .andExpect(status().isOk()).andExpect(cardCount(1))
      .andExpect(result -> assertThat(resultCards(result).getFirst().id()).isEqualTo("ip-malformed-host"));
  mvc.perform(get("/hosts").header("HX-Request","true").param("pingStatus","red"))
      .andExpect(status().isOk()).andExpect(cardCount(1))
      .andExpect(result -> assertThat(resultCards(result).getFirst().id()).isEqualTo("ip-failed-host"));
  mvc.perform(get("/grupos").header("HX-Request","true").param("pingStatus","neutral"))
      .andExpect(status().isOk()).andExpect(cardCount(1))
      .andExpect(result -> assertThat(resultCards(result).getFirst().id()).isEqualTo("ip-pending"));
 }
 @SuppressWarnings("unchecked")
 private static java.util.List<DashboardController.Card> resultCards(org.springframework.test.web.servlet.MvcResult result) {
  return (java.util.List<DashboardController.Card>)result.getModelAndView().getModel().get("cards");
 }
 private static org.springframework.test.web.servlet.ResultMatcher cardCount(int count) {
  return result -> assertThat((result.getResponse().getContentAsString().split("<article",-1).length-1)+(result.getResponse().getContentAsString().split("<tr class=\"host-row",-1).length-1)).isEqualTo(count);
 }
 private static org.springframework.test.web.servlet.ResultMatcher selectedGroup(String id) {
  String quote=Character.toString(34);
  return result -> assertThat(java.util.regex.Pattern.compile("<option(?=[^>]*value="+quote+java.util.regex.Pattern.quote(id)+quote+")(?=[^>]*selected)[^>]*>").matcher(result.getResponse().getContentAsString()).find()).isTrue();
 }
 @Test void persistsFailuresWithoutTurningThemIntoZero(){
  Host h=new Host("test","Teste","127.0.0.1",java.util.List.of());
  store.save(h,new PingProbe.Result(10d,null));store.save(h,new PingProbe.Result(null,"Tempo esgotado"));
  var s=analysis.analyze(java.util.List.of(h),System.currentTimeMillis()).get("test").stats();
  assertThat(s.hour()).isEqualTo(10d);assertThat(s.minute()).isEqualTo(10d);assertThat(s.current()).isNull();assertThat(s.failures()).isEqualTo(1);assertThat(s.attempts()).isEqualTo(2);
 }
}
