package br.com.scriptping.accesspoint;

import br.com.scriptping.zabbix.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.matchesPattern;

@WebMvcTest(AccessPointController.class)
@org.springframework.security.test.context.support.WithMockUser(roles = "ADMIN")
class AccessPointPageTests {
    @Autowired MockMvc mvc;
    @MockitoBean AccessPointService service;

    @Test void rendersLatencySummaryWithSharedNavigationAndEscapedEquipmentName() throws Exception {
        when(service.pageHost("42")).thenReturn(new Host("42", "AP <teste>", "192.0.2.1", List.of()));
        mvc.perform(get("/ap/42/latency/assessment-1")).andExpect(status().isOk())
            .andExpect(content().string(containsString("Resumo dos resultados")))
            .andExpect(content().string(containsString("AP &lt;teste&gt;")))
            .andExpect(content().string(containsString("data-assessment-id=\"assessment-1\"")))
            .andExpect(content().string(containsString("Média geral dos clientes")))
            .andExpect(content().string(containsString("3 clientes com maior latência adicional")))
            .andExpect(content().string(containsString("/ap/42")));
    }
    @Test void rendersUbiquitiWithoutVersionChoiceAndHidesWinbox() throws Exception {
        when(service.pageHost("42")).thenReturn(new Host("42", "LiteBeam", "192.0.2.42", List.of()));
        when(service.configuration("42")).thenReturn(new AccessPointService.ConfigurationView(
            "42", "LiteBeam", "192.0.2.42", "Ubiquiti", "reader", 22, true, true, "ACTIVE", "", false));
        mvc.perform(get("/ap/42")).andExpect(status().isOk())
            .andExpect(content().string(containsString("data-manufacturer=\"Ubiquiti\"")))
            .andExpect(content().string(containsString("Ubiquiti · Access Point")))
            .andExpect(content().string(containsString("id=\"ap-config-ssh-port\"")))
            .andExpect(content().string(not(containsString("RouterOS 6.x"))))
            .andExpect(content().string(matchesPattern("(?s).*<button[^>]*id=\"ap-winbox\"[^>]*hidden=\"hidden\"[^>]*>.*")));
    }

    @Test void rendersHostAndSharedNavigationWithoutExposingCredentials() throws Exception {
        when(service.pageHost("42")).thenReturn(new Host("42", "MikroTik São Paulo", "192.0.2.1", List.of()));
        when(service.host("42")).thenReturn(new Host("42", "MikroTik São Paulo", "192.0.2.1", List.of()));
        mvc.perform(get("/ap/42")).andExpect(status().isOk())
            .andExpect(content().string(containsString("MikroTik São Paulo")))
            .andExpect(content().string(containsString("data-host-id=\"42\"")))
            .andExpect(content().string(containsString("Wireless")))
            .andExpect(content().string(not(containsString("data-radio-visit"))))
            .andExpect(content().string(containsString("latency-execution-status")))
            .andExpect(content().string(containsString("latency-search")))
            .andExpect(content().string(not(containsString("latency-sidebar"))))
            .andExpect(content().string(containsString("latency-results-tab")))
            .andExpect(content().string(containsString("latency-results-panel")))
            .andExpect(content().string(containsString("latency-final-results")))
            .andExpect(content().string(not(containsString("latency-summary-link"))))
            .andExpect(content().string(containsString("id=\"ap-configure\"")))
            .andExpect(content().string(containsString("Configuração")))
            .andExpect(content().string(containsString("id=\"ap-config-dialog\"")))
            .andExpect(content().string(containsString("Excluir AP")))
            .andExpect(content().string(containsString("Clientes avaliados")))
            .andExpect(content().string(containsString("connected-clients")))
            .andExpect(content().string(containsString("signal-distribution")))
            .andExpect(content().string(containsString("Tempo conectado")))
            .andExpect(content().string(not(containsString("interface-search"))))
            .andExpect(content().string(not(containsString("wireless-search"))))
            .andExpect(content().string(not(containsString("filters-toggle"))));
        mvc.perform(get("/hosts/42")).andExpect(status().isNotFound());
        mvc.perform(get("/ap")).andExpect(status().isOk())
            .andExpect(content().string(containsString("Hosts disponíveis")))
            .andExpect(content().string(containsString("Definir como AP")))
            .andExpect(content().string(not(containsString("id=\"ap-config-dialog\""))))
            .andExpect(content().string(not(containsString("Configurar Access Point:"))));
    }

    @Test @org.springframework.security.test.context.support.WithMockUser(roles = "USER")
    void normalUsersDoNotSeeConfigurationActionOrDialog() throws Exception {
        when(service.pageHost("42")).thenReturn(new Host("42", "AP", "192.0.2.1", List.of()));
        mvc.perform(get("/ap/42")).andExpect(status().isOk())
            .andExpect(content().string(not(containsString("id=\"ap-configure\""))))
            .andExpect(content().string(not(containsString("id=\"ap-config-dialog\""))));
    }
}
