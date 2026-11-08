package br.com.scriptping.availability;

import br.com.scriptping.Host;

import br.com.scriptping.zabbix.ZabbixService;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AvailabilityDashboardTests extends MinuteTestSupport {
    @Test void queriesOnlyPublishedCache() {
        var cache=mock(AvailabilitySnapshotCache.class);
        var result=new AvailabilityDashboard.Result(host.groups(), queries.viewAt(List.of(host),5,now));
        when(cache.query(5,"g")).thenReturn(result);
        var dashboard=new AvailabilityDashboard(cache);
        var controller=new AvailabilityDashboardController(dashboard);
        assertThat(controller.data(5,"g").getBody().view().hosts()).hasSize(1);
        verify(cache).query(5,"g");
        assertThatThrownBy(()->controller.data(31,"")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verifyNoMoreInteractions(cache);
    }
    @Test void paginationSearchAndSortKeepFullSelectionTotals() {
        var report=queries.viewAt(List.of(),5,now).report();
        var metrics=report.total();
        var rows=java.util.stream.IntStream.range(0,1000).mapToObj(i->new AvailabilityDashboard.Row(
            "id-"+i,String.format("Roteador %04d",i),metrics,"10.0.0."+i,List.of(),"SEM_DADOS",false,false)).toList();
        var dashboard=mock(AvailabilityDashboard.class);
        when(dashboard.query(5,"")).thenReturn(new AvailabilityDashboard.Result(List.of(),new AvailabilityDashboard.View(report,rows)));
        var controller=new AvailabilityDashboardController(dashboard);
        var first=controller.dashboard(5,"","","name","asc",1).getBody();
        assertThat(first.page().pages()).isEqualTo(50);
        assertThat(first.view().hosts()).hasSize(20);
        assertThat(first.view().report()).isSameAs(report);
        var desc=controller.dashboard(5,"","","name","desc",1).getBody();
        assertThat(desc.view().hosts().getFirst().id()).isEqualTo("id-999");
        var found=controller.dashboard(5,"","10.0.0.999","name","asc",99).getBody();
        assertThat(found.view().hosts()).hasSize(1);
        assertThat(found.page().number()).isEqualTo(1);
        assertThat(found.page().selectedTotal()).isEqualTo(1000);
        assertThat(found.collectionStatus()).isEqualTo("SEM_DADOS");
        assertThat(controller.data(5,"").getHeaders().getCacheControl()).isEqualTo("no-store");
    }
}
