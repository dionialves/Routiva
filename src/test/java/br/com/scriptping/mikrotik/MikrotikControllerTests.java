package br.com.scriptping.mikrotik;

import br.com.scriptping.zabbix.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.web.server.ResponseStatusException;

class MikrotikControllerTests {
    private final ZabbixService inventory = mock(ZabbixService.class);
    private final MikrotikService service = mock(MikrotikService.class);
    private final org.springframework.test.web.servlet.MockMvc mvc = MockMvcBuilders
        .standaloneSetup(new MikrotikController(inventory, service)).build();
    private final Host host = new Host("42", "Router", "192.0.2.1", List.of());

    @Test void resolvesOnlyMonitoredHostsAndDisablesCaching() throws Exception {
        when(inventory.hosts()).thenReturn(List.of(host));
        when(service.query(host, "overview", null)).thenReturn(new MikrotikService.Snapshot(1, List.of(Map.of("identity", "Router")), ""));
        mvc.perform(get("/api/hosts/42/mikrotik/overview")).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.rows[0].identity").value("Router"));
        mvc.perform(get("/api/hosts/unknown/mikrotik/overview")).andExpect(status().isNotFound());
        mvc.perform(get("/api/hosts/42/mikrotik/system/reboot")).andExpect(status().isNotFound());
        verify(service, times(1)).query(any(), any(), any());
    }

    @Test void credentialsAreRequiredWithoutAttemptingConnection() {
        var real = new MikrotikService("", "", 8729, true);
        var error = assertThrows(ResponseStatusException.class, () -> real.query(host, "overview", null));
        assertEquals(503, error.getStatusCode().value());
        assertFalse(error.getReason().contains("secret"));
    }
}
