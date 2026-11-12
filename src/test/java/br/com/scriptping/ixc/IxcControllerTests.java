package br.com.scriptping.ixc;

import br.com.scriptping.mikrotik.MikrotikService;
import br.com.scriptping.zabbix.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class IxcControllerTests {
    @Test void modalIdentityIncludesPlanAndRouterAccessWithoutPppoePassword() throws Exception {
        var ixc = mock(IxcClient.class);
        var inventory = mock(ZabbixService.class);
        var mikrotik = mock(MikrotikService.class);
        var host = new Host("42", "AP", "192.0.2.1", List.of());
        when(inventory.hosts()).thenReturn(List.of(host));
        when(mikrotik.query(host, "wireless", null)).thenReturn(new MikrotikService.Snapshot(1,
            List.of(Map.of("mac-address", "AA:BB:CC:DD:EE:FF")), ""));
        var row = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
            "{\"login\":\"cliente\",\"ip\":\"192.0.2.15\",\"id_grupo\":\"7\",\"senha\":\"ppp-secret\",\"senha_router1\":\"router-secret\",\"porta_http\":\"8080\"}");
        when(ixc.findByMac("AA:BB:CC:DD:EE:FF")).thenReturn(List.of(new IxcClient.Login(row, "AA:BB:CC:DD:EE:FF")));
        when(ixc.findPlan("7")).thenReturn(new IxcClient.Plan("100M", "50M"));
        var mvc = MockMvcBuilders.standaloneSetup(new IxcController(ixc, inventory, mikrotik)).build();
        mvc.perform(get("/api/hosts/42/ixc/client").param("mac", "aabbccddeeff"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$[0].pppoe").value("cliente"))
            .andExpect(jsonPath("$[0].plan.download").value("100M"))
            .andExpect(jsonPath("$[0].equipmentPassword").value("router-secret"))
            .andExpect(jsonPath("$[0].pppoePassword").doesNotExist());
    }

    @Test void restrictsLookupsToMonitoredHostsAndTheirConnectedWirelessMacs() throws Exception {
        var ixc = mock(IxcClient.class);
        var inventory = mock(ZabbixService.class);
        var mikrotik = mock(MikrotikService.class);
        var host = new Host("42", "Router", "192.0.2.1", List.of());
        when(inventory.hosts()).thenReturn(List.of(host));
        when(mikrotik.query(host, "wireless", null)).thenReturn(new MikrotikService.Snapshot(1,
            List.of(Map.of("mac-address", "aa-bb-cc-dd-ee-ff")), ""));
        when(ixc.findByMac("AA:BB:CC:DD:EE:FF")).thenReturn(List.of());
        var mvc = MockMvcBuilders.standaloneSetup(new IxcController(ixc, inventory, mikrotik)).build();
        mvc.perform(get("/api/hosts/unknown/ixc/login").param("mac", "aabbccddeeff"))
            .andExpect(status().isNotFound()).andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get("/api/hosts/42/ixc/login").param("mac", "001122334455")).andExpect(status().isNotFound());
        verifyNoInteractions(ixc);
        mvc.perform(get("/api/hosts/42/ixc/login").param("mac", "aabbccddeeff"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        verify(ixc).findByMac("AA:BB:CC:DD:EE:FF");
    }
    @Test void automaticNameLookupReturnsOnlyDistinctPppoeNames() throws Exception {
        var ixc = mock(IxcClient.class);
        var inventory = mock(ZabbixService.class);
        var mikrotik = mock(MikrotikService.class);
        var host = new Host("42", "Router", "192.0.2.1", List.of());
        when(inventory.hosts()).thenReturn(List.of(host));
        when(mikrotik.query(host, "wireless", null)).thenReturn(new MikrotikService.Snapshot(1,
            List.of(Map.of("mac-address", "aa-bb-cc-dd-ee-ff")), ""));
        var row = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
            "{\"login\":\"cliente.pppoe\",\"senha\":\"secret\",\"senha_router1\":\"router-secret\"}");
        var login = new IxcClient.Login(row, "AA:BB:CC:DD:EE:FF");
        when(ixc.findByMacs(anyCollection())).thenReturn(List.of(login, login));
        var mvc = MockMvcBuilders.standaloneSetup(new IxcController(ixc, inventory, mikrotik)).build();
        mvc.perform(get("/api/hosts/42/ixc/login-names").param("mac", "aabbccddeeff"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(content().json("{\"AA:BB:CC:DD:EE:FF\":[\"cliente.pppoe\"]}"));
        mvc.perform(get("/api/hosts/unknown/ixc/login-names"))
            .andExpect(status().isNotFound());
        verify(ixc, times(1)).findByMacs(Set.of("AA:BB:CC:DD:EE:FF"));
    }

}
