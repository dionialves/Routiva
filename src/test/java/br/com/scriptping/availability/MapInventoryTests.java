package br.com.scriptping.availability;

import br.com.scriptping.maps.NetworkMapController;
import br.com.scriptping.maps.NetworkMapService;
import br.com.scriptping.maps.NetworkMapModels;
import br.com.scriptping.zabbix.Host;
import br.com.scriptping.zabbix.ZabbixClient;
import br.com.scriptping.zabbix.ZabbixService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class MapInventoryTests {
    @Test
    void resolvesHostAndGroupBindingsFromSelectedInventoryInOneIncidentQuery() {
        var inventory = mock(ZabbixService.class);
        var zabbix = mock(ZabbixClient.class);
        var controller = new NetworkMapController(new NetworkMapService(null, null, inventory, zabbix));
        var group = new Host.Group("g", "Rede");
        when(inventory.hosts()).thenReturn(List.of(
            new Host("1", "Switch", "10.0.0.1", List.of(group)),
            new Host("2", "Router", "10.0.0.2", List.of()),
            new Host("3", "Outro", "", List.of())));
        var incident = new ZabbixClient.Incident("Temperatura", 4);
        when(zabbix.activeIncidents(List.of("1", "2"))).thenReturn(Map.of("1", List.of(), "2", List.of(incident)));
        assertThat(controller.hosts()).contains(new NetworkMapModels.HostOption("2", "Router", "10.0.0.2"));
        var result = controller.states(new NetworkMapModels.Targets(Set.of("g"), Set.of("1", "2", "unknown")));
        assertThat(result).extracting(NetworkMapModels.HostState::id).containsExactly("1", "2");
        assertThat(result.getLast().incidents()).containsExactly(incident);
        verify(zabbix).activeIncidents(List.of("1", "2"));
    }
    @Test
    void summariesBatchAllGroupHostsAndRestrictRequestedBindings() {
        var inventory = mock(ZabbixService.class);
        var zabbix = mock(ZabbixClient.class);
        var service = new NetworkMapService(null, null, inventory, zabbix);
        var group = new Host.Group("g", "POP");
        when(inventory.hosts()).thenReturn(List.of(new Host("1", "Selected", "", List.of(group))));
        when(zabbix.mapHosts(Set.of("g"), Set.of("1"))).thenReturn(List.of(
            new ZabbixClient.MapHost("1", "Selected", List.of(group), "monitored"),
            new ZabbixClient.MapHost("2", "Other group member", List.of(group), "unknown"),
            new ZabbixClient.MapHost("3", "Disabled", List.of(group), "disabled")));
        when(zabbix.activeIncidents(List.of("1", "2", "3"), true)).thenReturn(Map.of("1", List.of(), "2", List.of()));
        var result = service.summaries(new NetworkMapModels.Targets(Set.of("g", "forbidden"), Set.of("1", "forbidden")));
        assertThat(result).extracting(NetworkMapModels.HostState::id).containsExactly("1", "2", "3");
        assertThat(result.getLast().monitoring()).isEqualTo("disabled");
        assertThat(result.getLast().incidents()).isNull();
        verify(zabbix).mapHosts(Set.of("g"), Set.of("1"));
        verify(zabbix).activeIncidents(List.of("1", "2", "3"), true);
    }
}
