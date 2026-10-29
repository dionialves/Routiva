package br.com.scriptping;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AvailabilityDashboardTests extends MinuteTestSupport {
    @Test void queriesOnlyRequestedGroupAndPeriodAndRejectsUnknownFilters() {
        var monitor=mock(MonitorService.class);
        var analysis=mock(PacketLossAnalysisService.class);
        var other=new Host("b","Other","127.0.0.2",List.of());
        when(monitor.hosts()).thenReturn(List.of(host,other));
        when(analysis.view(List.of(host),5)).thenReturn(queries.viewAt(List.of(host),5,now));
        var dashboard=new AvailabilityDashboard(monitor,analysis);
        var controller=new AvailabilityDashboardController(dashboard);
        assertThat(controller.data(5,"g").getBody().view().hosts()).hasSize(1);
        verify(analysis).view(List.of(host),5);
        verifyNoMoreInteractions(analysis);
        assertThatThrownBy(()->controller.data(30,"")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(()->controller.data(5,"missing")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
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
