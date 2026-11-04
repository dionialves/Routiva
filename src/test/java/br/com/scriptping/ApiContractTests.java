package br.com.scriptping;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** HTTP contracts: validation, authorization, payload and failure responses; no markup assertions. */
class ApiContractTests {
    private final MonitorService monitor = mock(MonitorService.class);
    private final PacketLossAnalysisService analysis = mock(PacketLossAnalysisService.class);
    private final ZabbixClient client = mock(ZabbixClient.class);
    private final ZabbixSettings settings = mock(ZabbixSettings.class);
    private final Host host = new Host("h", "Router", "10.0.0.1", List.of());
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(
            new HostDetailsController(monitor, new HostAvailabilityService(analysis)),
            new ZabbixSettingsController(settings, client, monitor)).build();
        when(monitor.hosts()).thenReturn(List.of(host));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 8, 24, 48})
    void supportedHostPeriodsReturnDataWithoutCaching(int hours) throws Exception {
        var metrics = new HostAvailabilityService.Counts().metrics();
        var report = new HostAvailabilityService.Report(0, 60000, 60000, metrics, List.of(), "v1", 60000L, null, 0);
        when(analysis.availability(host, hours)).thenReturn(report);
        mvc.perform(get("/api/hosts/h/availability").param("hours", String.valueOf(hours)))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.version").value("v1"))
            .andExpect(jsonPath("$.total.totalPings").value(0))
            .andExpect(jsonPath("$.total.technical").isEmpty());
        verify(analysis).availability(host, hours);
    }

    @Test
    void missingPeriodDefaultsTo24Hours() throws Exception {
        mvc.perform(get("/api/hosts/h/availability")).andExpect(status().isOk());
        verify(analysis).availability(host, 24);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "2", "49", "abc", "2147483648"})
    void invalidHostPeriodNeverQueriesStorage(String hours) throws Exception {
        mvc.perform(get("/api/hosts/h/availability").param("hours", hours)).andExpect(status().isBadRequest());
        verifyNoInteractions(analysis);
    }

    @Test
    void unknownHostReturns404WithoutReadingHistory() throws Exception {
        mvc.perform(get("/api/hosts/unknown/availability")).andExpect(status().isNotFound());
        verifyNoInteractions(analysis);
    }

    @ParameterizedTest
    @ValueSource(strings = {"conexao", "teste", "sincronizar", "selecao"})
    void everyMutationRejectsMissingOrForeignCsrfBeforeSideEffects(String action) throws Exception {
        var session = session();
        clearInvocations(monitor);
        mvc.perform(post("/configuracao/zabbix/api/" + action).session(session)).andExpect(status().isBadRequest());
        mvc.perform(post("/configuracao/zabbix/api/" + action).session(session).param("csrf", "other"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/configuracao/zabbix/api/" + action).param("csrf", "token"))
            .andExpect(status().isForbidden());
        verifyNoInteractions(client, monitor, settings);
    }

    @Test
    void unknownActionReturns404() throws Exception {
        mvc.perform(post("/configuracao/zabbix/api/unknown").session(session()).param("csrf", "token"))
            .andExpect(status().isNotFound());
        verifyNoInteractions(client, settings);
    }

    @Test
    void failedConnectionTestStopsSynchronizationAndHidesUnexpectedErrorDetails() throws Exception {
        doThrow(new RuntimeException("password=secret internal trace")).when(client).testConnection();
        mvc.perform(post("/configuracao/zabbix/api/sincronizar").session(session()).param("csrf", "token"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Não foi possível acessar o Zabbix. Verifique endereço, credenciais e disponibilidade da API."));
        verify(monitor, never()).sync();
    }

    @Test
    void syncTestsConnectionBeforeUpdatingInventory() throws Exception {
        when(monitor.status()).thenReturn("Sincronização concluída");
        mvc.perform(post("/configuracao/zabbix/api/sincronizar").session(session()).param("csrf", "token"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("Sincronização concluída"));
        var order = inOrder(client, monitor);
        order.verify(client).testConnection();
        order.verify(monitor).sync();
    }

    private MockHttpSession session() {
        var session = new MockHttpSession();
        session.setAttribute("zabbixCsrf", "token");
        return session;
    }
}
