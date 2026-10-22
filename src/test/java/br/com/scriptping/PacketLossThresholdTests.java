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

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:threshold;DB_CLOSE_DELAY=-1","spring.datasource.username=sa","spring.datasource.password=","zabbix.url=","zabbix.username=","zabbix.password=","monitor.packet-loss-ignore-percent=1"})
@AutoConfigureMockMvc
class PacketLossThresholdTests {
    @Autowired MockMvc mvc;
    @MockitoBean MonitorService monitor;
    @MockitoBean SampleStore store;

    Host host(String id) {return new Host(id,id,"10.4.0.1",List.of(new Host.Group(id+"-group",id)));}
    SampleStore.Stats stats(long attempts,long losses,long minuteAttempts,long minuteLosses) {
        return new SampleStore.Stats(10d,10d,10d,System.currentTimeMillis(),null,attempts,losses,attempts,losses,minuteAttempts,minuteLosses);
    }
    @SuppressWarnings("unchecked")
    List<DashboardController.Card> cards(String path,String status) throws Exception {
        var result=mvc.perform(get(path).header("HX-Request","true").param("pingStatus",status)).andExpect(status().isOk()).andReturn();
        return (List<DashboardController.Card>)result.getModelAndView().getModel().get("cards");
    }

    @Test void ignoredPercentagesDoNotTriggerLossFiltersOrWarningsInEitherTable() throws Exception {
        var below=host("below");var exact=host("exact");var above=host("above");
        when(monitor.hosts()).thenReturn(List.of(below,exact,above));
        when(store.stats(anyList(),anyLong())).thenReturn(Map.of(below.id(),stats(200,1,200,1),exact.id(),stats(100,1,100,1),above.id(),stats(100,2,100,2)));
        for(String page:List.of("/hosts","/grupos")) {
            assertThat(cards(page,"packet-loss")).singleElement().satisfies(c->{
                assertThat(c.name()).isEqualTo("above");assertThat(c.color()).isEqualTo("red");
            });
            assertThat(cards(page,"normal")).hasSize(2).allSatisfy(c->{
                assertThat(c.lossPercentage()).isEqualTo("0,00%");
                assertThat(c.minuteLossPercentage()).isEqualTo("0,00%");
                assertThat(c.color()).isEqualTo("green");
                assertThat(c.lossTitle()).doesNotContain("1 de");
            });
        }
    }

    @Test void toleranceUsesEachWindowAndTheAggregatedGroupPercentageIndependently() throws Exception {
        var group=new Host.Group("combined","Combinado");
        var a=new Host("window-a","A","10.4.1.1",List.of(group));
        var b=new Host("window-b","B","10.4.1.2",List.of(group));
        when(monitor.hosts()).thenReturn(List.of(a,b));
        when(store.stats(anyList(),anyLong())).thenReturn(Map.of(a.id(),stats(100,2,10,1),b.id(),stats(900,0,90,0)));
        var grouped=cards("/grupos","all").getFirst();
        assertThat(grouped.lossPercentage()).isEqualTo("0,00%");
        assertThat(grouped.minuteLossPercentage()).isEqualTo("0,00%");
        assertThat(grouped.color()).isEqualTo("green");
        var significant=cards("/hosts","packet-loss");
        assertThat(significant).singleElement().satisfies(c->{
            assertThat(c.lossPercentage()).isEqualTo("2,00%");
            assertThat(c.minuteLossPercentage()).isEqualTo("10,00%");
        });
    }
}
