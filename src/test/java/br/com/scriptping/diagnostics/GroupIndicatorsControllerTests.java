package br.com.scriptping.diagnostics;

import br.com.scriptping.availability.*;
import br.com.scriptping.zabbix.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GroupIndicatorsControllerTests {
    @Test void deduplicatesEventsAcrossHostsAndPrioritizesActiveCriticalIncident() {
        var cache = mock(AvailabilitySnapshotCache.class);
        var inventory = mock(ZabbixService.class);
        var client = mock(ZabbixClient.class);
        var group = new Host.Group("g", "São Paulo");
        when(inventory.hosts()).thenReturn(List.of(new Host("a","A","1.1.1.1",List.of(group)), new Host("b","B","1.1.1.2",List.of(group))));
        var counts = new HostAvailabilityService.Counts(); counts.h = 100; counts.sum = 200;
        when(cache.groupTotals(eq(1440), anyList())).thenReturn(new AvailabilitySnapshotCache.GroupTotals(1,2,"v", List.of(
            new AvailabilitySnapshotCache.GroupTotal("g", "São Paulo", 2, counts.metrics(), 60000, "NORMAL"))));
        var incident = new ZabbixClient.Incident("Falha", 5, "event1", 1);
        when(client.activeIncidents(anyList())).thenReturn(Map.of("a",List.of(incident),"b",List.of(incident)));
        var controller = new GroupIndicatorsController(cache,inventory,client,4,2);
        var response = controller.indicators(1440,"sao","CRITICO","incidents","desc",99).getBody();
        assertThat(response.total()).isEqualTo(1);
        assertThat(response.number()).isEqualTo(1);
        var row = response.rows().getFirst();
        assertThat(row.incidents()).isEqualTo(1);
        assertThat(row.severities()).containsEntry(5,1L);
        assertThat(row.status()).isEqualTo("CRITICO");
        verify(client).activeIncidents(List.of("a","b"));
        assertThat(controller.indicators(1440,"g","NORMAL","name","asc",1).getBody().rows()).isEmpty();
        when(client.activeIncidents(anyList())).thenThrow(new IllegalStateException());
        var failed = controller.indicators(1440,"g","","name","asc",1).getBody();
        assertThat(failed.rows().getFirst().incidents()).isNull();
        assertThat(failed.warning()).contains("Incidentes indisponíveis");
    }

    @Test void paginationAndNumericOrderingRunBeforeReturningRows() {
        var cache = mock(AvailabilitySnapshotCache.class);
        var inventory = mock(ZabbixService.class);
        var client = mock(ZabbixClient.class);
        when(inventory.hosts()).thenReturn(List.of());
        var metrics = new HostAvailabilityService.Counts().metrics();
        var rows = new ArrayList<AvailabilitySnapshotCache.GroupTotal>();
        for (int i=0; i<45; i++) rows.add(new AvailabilitySnapshotCache.GroupTotal("g"+i,"Grupo "+i,i,metrics,0,"SEM_DADOS"));
        when(cache.groupTotals(eq(1440),anyList())).thenReturn(new AvailabilitySnapshotCache.GroupTotals(0,0,"v",rows));
        var controller = new GroupIndicatorsController(cache,inventory,client,4,2);
        var result = controller.indicators(1440,"","SEM_DADOS","hosts","desc",2).getBody();
        assertThat(result.total()).isEqualTo(45);
        assertThat(result.pages()).isEqualTo(3);
        assertThat(result.rows()).hasSize(20);
        assertThat(result.rows().getFirst().hosts()).isEqualTo(24);
    }
}
