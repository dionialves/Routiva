package br.com.scriptping;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:test;DB_CLOSE_DELAY=-1", "zabbix.url=", "zabbix.username=", "zabbix.password=", "spring.datasource.username=sa", "spring.datasource.password="})
@AutoConfigureMockMvc
class PacketLossTests {
    @Autowired MockMvc mvc;
    @Autowired SampleStore store;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean MonitorService monitor;

    private Host host(String id, String ip) {
        return new Host(id,id,ip,List.of(new Host.Group(id+"-group",id+" group")));
    }

    private void sample(Host host, long at, Double latency, String error) {
        jdbc.update("INSERT INTO ping_sample(host_id,target,measured_at,latency,error) VALUES(?,?,?,?,?)",host.id(),host.ip(),at,latency,error);
    }

    @SuppressWarnings("unchecked")
    private List<DashboardController.Card> cards(String path,String filter) throws Exception {
        var result=mvc.perform(get(path).header("HX-Request","true").param("pingStatus",filter))
            .andExpect(status().isOk()).andReturn();
        return (List<DashboardController.Card>)result.getModelAndView().getModel().get("cards");
    }

    @Test void fiveLostPingsKeepRecoveredHostAndGroupRedUntilTheyExpire() throws Exception {
        var host=host("loss-five","10.9.0.1");
        long now=System.currentTimeMillis();
        for(int i=0;i<5;i++) sample(host,now-120000-i,null,"Tempo esgotado");
        for(int i=0;i<55;i++) sample(host,now-1000-i,10d,null);
        when(monitor.hosts()).thenReturn(List.of(host));
        for(String path:List.of("/hosts","/grupos")) {
            var card=cards(path,"all").getFirst();
            assertThat(card.color()).isEqualTo("red");
            assertThat(card.state()).isEqualTo("Com perda de pacotes");
            assertThat(card.current()).isEqualTo(10d);
            assertThat(card.hour()).isEqualTo(10d);
            assertThat(card.lostPackets()).isEqualTo(5);
            assertThat(card.lossAttempts()).isEqualTo(60);
            assertThat(card.lossPercentage()).isEqualTo("8,33%");
        }
        mvc.perform(get("/hosts")).andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("5 de 60 pings")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Com perda de pacotes")));
        jdbc.update("UPDATE ping_sample SET measured_at=? WHERE host_id=? AND latency IS NULL",now-301000,host.id());
        for(String path:List.of("/hosts","/grupos")) {
            var card=cards(path,"all").getFirst();
            assertThat(card.state()).isEqualTo("Normal");
            assertThat(card.color()).isEqualTo("green");
            assertThat(card.lossPercentage()).isEqualTo("0,00%");
        }
    }

    @Test void statusesAndFiltersDistinguishLatencyLossAndBoth() throws Exception {
        var normal=host("loss-normal","10.9.1.1");
        var high=host("loss-high","10.9.1.2");
        var loss=host("loss-only","10.9.1.3");
        var both=host("loss-both","10.9.1.4");
        long now=System.currentTimeMillis();
        sample(normal,now,14.99,null);
        sample(high,now,15d,null);
        sample(loss,now-1000,null,"Sem resposta ICMP");sample(loss,now,10d,null);
        sample(both,now-1000,null,"Tempo esgotado");sample(both,now,25d,null);
        when(monitor.hosts()).thenReturn(List.of(normal,high,loss,both));
        for(String path:List.of("/hosts","/grupos")) {
            assertThat(cards(path,"all")).extracting(DashboardController.Card::state)
                .containsExactlyInAnyOrder("Normal","Latência alta","Com perda de pacotes","Com latência e perda de pacotes");
            assertThat(cards(path,"normal")).singleElement().satisfies(c->assertThat(c.color()).isEqualTo("green"));
            assertThat(cards(path,"yellow")).singleElement().satisfies(c->assertThat(c.state()).isEqualTo("Latência alta"));
            assertThat(cards(path,"high-latency")).hasSize(2);
            assertThat(cards(path,"packet-loss")).hasSize(2).allSatisfy(c->assertThat(c.color()).isEqualTo("red"));
            assertThat(cards(path,"latency-and-loss")).singleElement().satisfies(c->assertThat(c.state()).isEqualTo("Com latência e perda de pacotes"));
        }
        mvc.perform(get("/hosts").header("HX-Request","true").param("groupId",both.id()+"-group").param("q","both").param("pingStatus","packet-loss"))
            .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Com latência e perda de pacotes")));
    }

    @Test void lossWindowHasExactBoundaryExcludesOtherTargetsAndLocalExecutionErrors() {
        var host=host("loss-boundary","10.9.2.1");
        long now=System.currentTimeMillis();
        sample(host,now-300000,null,"Tempo esgotado");
        sample(host,now-300001,null,"Sem resposta ICMP");
        sample(host,now-1000,12d,null);
        sample(host,now-500,null,"Falha ao executar ping");
        sample(host,now-400,null,"Interrompido");
        sample(host,now+10000,null,"Tempo esgotado");
        sample(new Host(host.id(),host.name(),"10.9.2.99",host.groups()),now,null,"Tempo esgotado");
        var stats=store.stats(List.of(host),now).get(host.id());
        assertThat(stats.lossesFiveMinutes()).isEqualTo(1);
        assertThat(stats.attemptsFiveMinutes()).isEqualTo(2);
        assertThat(stats.hour()).isEqualTo(12d);
        stats=store.stats(List.of(host),now+1).get(host.id());
        assertThat(stats.lossesFiveMinutes()).isZero();
        assertThat(stats.attemptsFiveMinutes()).isEqualTo(1);
    }

    @Test void groupLossUsesPacketTotalsAndExcludesInvalidIpHosts() throws Exception {
        var a=host("loss-weight-a","10.9.3.1");
        var b=new Host("loss-weight-b","B","10.9.3.2",a.groups());
        var invalid=new Host("loss-weight-invalid","Sem IP","",a.groups());
        long now=System.currentTimeMillis();
        sample(a,now-1000,null,"Tempo esgotado");sample(a,now,10d,null);
        for(int i=0;i<8;i++) sample(b,now-i,10d,null);
        for(int i=0;i<10;i++) sample(invalid,now-i,null,"Tempo esgotado");
        when(monitor.hosts()).thenReturn(List.of(a,b,invalid));
        var group=cards("/grupos","all").getFirst();
        assertThat(group.lossAttempts()).isEqualTo(10);
        assertThat(group.lostPackets()).isEqualTo(1);
        assertThat(group.lossPercentage()).isEqualTo("10,00%");
        assertThat(group.color()).isEqualTo("red");
        assertThat(cards("/hosts","gray")).singleElement().satisfies(c->{
            assertThat(c.lostPackets()).isZero();assertThat(c.lossAttempts()).isZero();assertThat(c.lossPercentage()).isEqualTo("—");
        });
    }

    @Test void totalLossAndCollectorFailuresRemainDistinct() throws Exception {
        var lost=host("loss-total","10.9.4.1");
        var failed=host("loss-collector","10.9.4.2");
        long now=System.currentTimeMillis();
        sample(lost,now,null,"Tempo esgotado");
        sample(failed,now,null,"Falha ao executar ping");
        when(monitor.hosts()).thenReturn(List.of(lost,failed));
        var losses=cards("/hosts","packet-loss");
        assertThat(losses).singleElement().satisfies(c->{
            assertThat(c.id()).isEqualTo(lost.id());
            assertThat(c.lossPercentage()).isEqualTo("100,00%");
            assertThat(c.state()).isEqualTo("Com perda de pacotes");
            assertThat(c.hour()).isNull();assertThat(c.current()).isNull();
        });
        assertThat(cards("/hosts","red")).filteredOn(c->c.id().equals(failed.id())).singleElement().satisfies(c->{
            assertThat(c.state()).isEqualTo("Falha na coleta");
            assertThat(c.lossAttempts()).isZero();
            assertThat(c.lossPercentage()).isEqualTo("—");
        });
    }
}
