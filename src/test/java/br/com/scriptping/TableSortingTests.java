package br.com.scriptping;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:test;DB_CLOSE_DELAY=-1", "zabbix.url=", "zabbix.username=", "zabbix.password=", "spring.datasource.username=sa", "spring.datasource.password="})
@AutoConfigureMockMvc
class TableSortingTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean MonitorService monitor;

    Host host(String id,String name,String ip) {
        return new Host(id,name,ip,List.of(new Host.Group(id+"-group",name)));
    }
    void sample(Host host,long time,Double latency,String error) {
        jdbc.update("INSERT INTO ping_sample(host_id,target,measured_at,latency,error) VALUES(?,?,?,?,?)",host.id(),host.ip(),time,latency,error);
    }
    @SuppressWarnings("unchecked")
    List<DashboardController.Card> sorted(String path,String column,String direction) throws Exception {
        var result=mvc.perform(get(path).header("HX-Request","true").param("sort",column).param("direction",direction))
            .andExpect(status().isOk()).andReturn();
        return (List<DashboardController.Card>)result.getModelAndView().getModel().get("cards");
    }

    @Test void numericLatencySortingAndMissingValuesWorkInBothTablesAndDirections() throws Exception {
        var a=host("sort-ten","A dez","10.8.0.10");
        var b=host("sort-two","B dois","10.8.0.2");
        var c=host("sort-hundred","C cem","10.8.0.100");
        var d=host("sort-empty","D vazio","");
        long now=System.currentTimeMillis();
        sample(a,now,10d,null);sample(b,now,2d,null);sample(c,now,100d,null);
        when(monitor.hosts()).thenReturn(List.of(a,b,c,d));
        for(String path:List.of("/hosts","/grupos")) {
            for(String column:List.of("hour","minute","current")) {
                assertThat(sorted(path,column,"asc")).extracting(DashboardController.Card::name)
                    .containsExactly("B dois","A dez","C cem","D vazio");
                assertThat(sorted(path,column,"desc")).extracting(DashboardController.Card::name)
                    .containsExactly("C cem","A dez","B dois","D vazio");
            }
        }
        mvc.perform(get("/hosts").param("sort","current").param("direction","desc").param("q","dez"))
            .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("aria-sort=\"descending\"")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"sort\" value=\"current\"")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"direction\" value=\"desc\"")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("data-sort=\"current\" data-direction=\"asc\"")));
    }

    @Test void packetLossSortUsesPercentagesRatherThanCountsOrFormattedText() throws Exception {
        var two=host("sort-loss-two","A dois por cento","10.8.1.1");
        var ten=host("sort-loss-ten","B dez por cento","10.8.1.2");
        var empty=host("sort-loss-empty","C sem dados","");
        long now=System.currentTimeMillis();
        for(int i=0;i<100;i++) sample(two,now-1000-i,i<2?null:10d,i<2?"Tempo esgotado":null);
        for(int i=0;i<10;i++) sample(ten,now-1000-i,i<1?null:10d,i<1?"Tempo esgotado":null);
        when(monitor.hosts()).thenReturn(List.of(two,ten,empty));
        for(String path:List.of("/hosts","/grupos")) {
            for(String column:List.of("lossHour","lossMinute")) {
                assertThat(sorted(path,column,"asc")).extracting(DashboardController.Card::name)
                    .containsExactly(two.name(),ten.name(),empty.name());
                assertThat(sorted(path,column,"desc")).extracting(DashboardController.Card::name)
                    .containsExactly(ten.name(),two.name(),empty.name());
            }
        }
    }

    @Test void ipsSortNumericallyAndTimestampsUseTheirFullDate() throws Exception {
        var ten=host("sort-ip-ten","A dez","10.8.2.10");
        var two=host("sort-ip-two","B dois","10.8.2.2");
        var empty=host("sort-ip-empty","C vazio","");
        long now=System.currentTimeMillis();
        sample(ten,now-86400000,10d,null);sample(two,now,10d,null);
        when(monitor.hosts()).thenReturn(List.of(ten,two,empty));
        assertThat(sorted("/hosts","ip","asc")).extracting(DashboardController.Card::id).containsExactly(two.id(),ten.id(),empty.id());
        assertThat(sorted("/hosts","ip","desc")).extracting(DashboardController.Card::id).containsExactly(ten.id(),two.id(),empty.id());
        assertThat(sorted("/hosts","updated","asc")).extracting(DashboardController.Card::id).containsExactly(ten.id(),two.id(),empty.id());
        assertThat(sorted("/hosts","updated","desc")).extracting(DashboardController.Card::id).containsExactly(two.id(),ten.id(),empty.id());
    }

    @Test void groupMemberCountSortsNumericallyAndUnknownParametersFallBackSafely() throws Exception {
        var small=new Host.Group("sort-small","Z pequeno");
        var large=new Host.Group("sort-large","A grande");
        List<Host> inventory=new ArrayList<>();
        for(int i=0;i<12;i++) inventory.add(new Host("sort-member-"+i,"Host "+i,"10.8.3."+(i+1),i<2?List.of(small,large):List.of(large)));
        when(monitor.hosts()).thenReturn(inventory);
        assertThat(sorted("/grupos","members","asc")).extracting(DashboardController.Card::members).containsExactly(2,12);
        assertThat(sorted("/grupos","members","desc")).extracting(DashboardController.Card::members).containsExactly(12,2);
        assertThat(sorted("/grupos","invalid","invalid")).extracting(DashboardController.Card::name).containsExactly("A grande","Z pequeno");
        mvc.perform(get("/hosts").header("HX-Request","true").param("groupId",small.id()).param("sort","ip").param("direction","desc"))
            .andExpect(status().isOk()).andExpect(result->{
                var rows=(List<?>)result.getModelAndView().getModel().get("cards");
                assertThat(rows).hasSize(2);
                assertThat(((DashboardController.Card)rows.getFirst()).ip()).isEqualTo("10.8.3.2");
            });
    }
}
