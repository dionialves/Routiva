package br.com.scriptping;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:test;DB_CLOSE_DELAY=-1", "zabbix.url=", "zabbix.username=", "zabbix.password=", "spring.datasource.username=sa", "spring.datasource.password="})
@AutoConfigureMockMvc
class DashboardTests {
 @Autowired MockMvc mvc;
 @Autowired SampleStore store;
 @org.springframework.test.context.bean.override.mockito.MockitoBean MonitorService monitor;
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
  mvc.perform(get("/")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("hx-trigger=\"every 2s\"")));
  mvc.perform(get("/dashboard")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Nenhum host")));
  mvc.perform(get("/webjars/htmx.org/2.0.4/dist/htmx.min.js")).andExpect(status().isOk());
 }
 @Test void rendersPopulatedCards() throws Exception {
  Host h=new Host("render", "Servidor exemplo", "127.0.0.1", java.util.List.of(new Host.Group("g1","Produção")));
  store.save(h,new PingProbe.Result(16d,null));
  org.mockito.Mockito.when(monitor.hosts()).thenReturn(java.util.List.of(h));
  mvc.perform(get("/dashboard")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Servidor exemplo"))).andExpect(content().string(org.hamcrest.Matchers.containsString("Produção"))).andExpect(content().string(org.hamcrest.Matchers.containsString("16,00"))).andExpect(content().string(org.hamcrest.Matchers.containsString("card yellow")));
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
       .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"pingStatus\"")));
   for(var pair:java.util.Map.of("green","Ágil","yellow","Médio","red","Lento","neutral","Novo").entrySet()) {
    mvc.perform(get(path).header("HX-Request","true").param("pingStatus",pair.getKey()))
       .andExpect(status().isOk())
       .andExpect(content().string(org.hamcrest.Matchers.containsString("<h3>"+prefix+" "+pair.getValue()+"</h3>")))
       .andExpect(content().string(org.hamcrest.Matchers.containsString("1 de 4")))
       .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("<form"))));
   }
   mvc.perform(get(path).header("HX-Request","true").param("q","AGIL").param("pingStatus","green"))
       .andExpect(status().isOk())
       .andExpect(content().string(org.hamcrest.Matchers.containsString("<h3>"+prefix+" Ágil</h3>")))
       .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("<h3>"+prefix+" Médio</h3>"))));
   mvc.perform(get(path).header("HX-Request","true").param("q","agil").param("pingStatus","red"))
       .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Nenhum resultado")));
  }
  store.save(green,new PingProbe.Result(null,"Tempo esgotado"));
  mvc.perform(get("/hosts").header("HX-Request","true").param("q","agil").param("pingStatus","green"))
      .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Nenhum resultado")));
  mvc.perform(get("/grupos").header("HX-Request","true").param("q","agil").param("pingStatus","red"))
      .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("<h3>Grupo Ágil</h3>")));
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
      .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("<h3>Outro nome</h3>"))));
 }
 @Test void persistsFailuresWithoutTurningThemIntoZero(){
  Host h=new Host("test","Teste","127.0.0.1",java.util.List.of());
  store.save(h,new PingProbe.Result(10d,null));store.save(h,new PingProbe.Result(null,"Tempo esgotado"));
  var s=store.stats(java.util.List.of(h),System.currentTimeMillis()).get("test");
  assertThat(s.hour()).isEqualTo(10d);assertThat(s.minute()).isEqualTo(10d);assertThat(s.current()).isNull();assertThat(s.failures()).isEqualTo(1);assertThat(s.attempts()).isEqualTo(2);
 }
}
