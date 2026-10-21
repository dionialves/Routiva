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
 @Test void persistsFailuresWithoutTurningThemIntoZero(){
  Host h=new Host("test","Teste","127.0.0.1",java.util.List.of());
  store.save(h,new PingProbe.Result(10d,null));store.save(h,new PingProbe.Result(null,"Tempo esgotado"));
  var s=store.stats(java.util.List.of(h),System.currentTimeMillis()).get("test");
  assertThat(s.hour()).isEqualTo(10d);assertThat(s.minute()).isEqualTo(10d);assertThat(s.current()).isNull();assertThat(s.failures()).isEqualTo(1);assertThat(s.attempts()).isEqualTo(2);
 }
}
