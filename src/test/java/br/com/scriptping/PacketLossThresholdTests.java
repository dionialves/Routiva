package br.com.scriptping;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static br.com.scriptping.PacketLossAnalysis.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:presentation;DB_CLOSE_DELAY=-1","spring.datasource.username=sa","spring.datasource.password=","zabbix.url=","zabbix.username=","zabbix.password="})
@AutoConfigureMockMvc
class PacketLossThresholdTests {
    @Autowired MockMvc mvc;
    @MockitoBean MonitorService monitor;
    @MockitoBean PacketLossAnalysisService analysis;
    Host host(String id){return new Host(id,id,"10.4.0.1",List.of(new Host.Group(id+"-group",id)));}
    PacketLossAnalysis result(long hourLoss,long minuteLoss,Quality minute,Quality hour,Level level) {
        var stats=new SampleStore.Stats(10d,10d,10d,System.currentTimeMillis(),null,3600,hourLoss,3600,hourLoss,60,minuteLoss,10d,10d,10d,3600,hourLoss,3600,hourLoss,300,minuteLoss);
        return new PacketLossAnalysis(stats,minute,hour,0,1,1,false,level,true,true,true);
    }
    @SuppressWarnings("unchecked") List<DashboardController.Card> cards(String page,String filter)throws Exception {
        var result=mvc.perform(get(page).header("HX-Request","true").param("pingStatus",filter)).andExpect(status().isOk()).andReturn();
        return (List<DashboardController.Card>)result.getModelAndView().getModel().get("cards");
    }
    @Test void realPercentagesAndIndependentQualityControlColorsInBothTables()throws Exception {
        var isolated=host("isolated");var history=host("historical");var attention=host("attention");var critical=host("critical");
        when(monitor.hosts()).thenReturn(List.of(isolated,history,attention,critical));
        when(analysis.analyze(anyList(),anyLong())).thenReturn(Map.of(
            isolated.id(),result(1,1,Quality.EVENTO_ISOLADO,Quality.NORMAL,Level.NORMAL),
            history.id(),result(4,0,Quality.NORMAL,Quality.DEGRADADO,Level.NORMAL),
            attention.id(),result(2,2,Quality.ATENCAO,Quality.ATENCAO,Level.NORMAL),
            critical.id(),result(6,6,Quality.CRITICO,Quality.DEGRADADO,Level.CRITICO)));
        for(String page:List.of("/hosts","/grupos")) {
            assertThat(cards(page,"green")).isEmpty();
            assertThat(cards(page,"yellow")).filteredOn(c->c.name().equals("isolated")).singleElement().satisfies(c->{
                assertThat(c.lossPercentage()).isEqualTo("0,03%");assertThat(c.minuteLossPercentage()).isEqualTo("0,03%");assertThat(c.fiveMinuteLossPercentage()).isEqualTo("0,33%");
                assertThat(c.state()).isEqualTo("Perda de pacotes: degradado");assertThat(c.lossTitle()).isEqualTo("1 de 3600 pings");
            });
            assertThat(cards(page,"normal")).isEmpty();
            assertThat(cards(page,"yellow")).hasSize(2);
            assertThat(cards(page,"red")).hasSize(2);
            assertThat(cards(page,"packet-loss")).hasSize(4);
        }
    }
    @Test void eachDisplayedWindowKeepsRecoveredHostsAboveTheConfiguredLimit()throws Exception {
        var h=host("window");
        when(monitor.hosts()).thenReturn(List.of(h));
        for(int window=0;window<3;window++) {
            for(long losses:new long[]{0,1,2,10}) {
                long eight=window==0?losses:0, hour=window==1?losses:0, five=window==2?losses:0;
                var stats=new SampleStore.Stats(10d,10d,10d,System.currentTimeMillis(),null,2000,hour,2000,hour,60,0,10d,10d,10d,2000,eight,2000,hour,2000,five);
                var recovered=new PacketLossAnalysis(stats,Quality.NORMAL,Quality.NORMAL,0,0,0,false,Level.NORMAL,true,true,true);
                when(analysis.analyze(anyList(),anyLong())).thenReturn(Map.of(h.id(),recovered));
                for(String page:List.of("/hosts","/grupos")) {
                    var card=cards(page,"all").getFirst();
                    assertThat(card.color()).as("window %s, losses %s",window,losses).isEqualTo(losses<=1?"green":losses<10?"yellow":"red");
                    assertThat(card.lossIssue()).isEqualTo(losses>1);
                }
            }
        }
    }
    @Test void aGroupsSituationUsesItsWorstMemberWhilePercentagesRemainAggregated()throws Exception {
        var group=new Host.Group("mixed","Misto");
        var a=new Host("mixed-a","A","10.4.1.1",List.of(group));var b=new Host("mixed-b","B","10.4.1.2",List.of(group));
        when(monitor.hosts()).thenReturn(List.of(a,b));
        when(analysis.analyze(anyList(),anyLong())).thenReturn(Map.of(a.id(),result(3,3,Quality.DEGRADADO,Quality.NORMAL,Level.CRITICO),b.id(),result(0,0,Quality.NORMAL,Quality.NORMAL,Level.NORMAL)));
        var combined=cards("/grupos","all").getFirst();
        assertThat(combined.color()).isEqualTo("red");assertThat(combined.lossPercentage()).isEqualTo("0,04%");
        assertThat(combined.minuteLossPercentage()).isEqualTo("0,04%");assertThat(combined.fiveMinuteLossPercentage()).isEqualTo("0,50%");assertThat(combined.lossIssue()).isTrue();
    }
}
