package br.com.scriptping.client.radio;

import br.com.scriptping.accesspoint.AccessPointService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(RadioClientController.class)
@WithMockUser
class RadioClientPageTests {
    @Autowired MockMvc mvc;
    @MockitoBean RadioClientService clients;
    @MockitoBean AccessPointService aps;
    @Test void rendersSharedTableModalAndAccessibleTabsExactlyOnce() throws Exception {
        String html = mvc.perform(get("/clientes/radio")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(html.indexOf("incident-radio-group-picker")).isLessThan(html.indexOf("incident-radio-ap-picker"));
        assertThat(html.indexOf("incident-radio-ap-picker")).isLessThan(html.indexOf("incident-radio-client-picker"));
        assertThat(html.indexOf("incident-radio-client-picker")).isLessThan(html.indexOf("incident-radio-status-picker"));
        assertThat(html).contains("Clientes Rádio", "hosts-table", "form-dialog", "role=\"tablist\"", "aria-controls=\"radio-history\"", "Vincular login");
        assertThat(html).contains("Informações do cliente", "Visão geral", "Histórico", "Sinal (RSSI)",
            "id=\"radio-summary-signal\"", "id=\"radio-summary-status\"", "id=\"radio-summary-latency\"", "id=\"radio-summary-loss\"");
        assertThat(html).contains("app-modal-footer", "id=\"radio-cancel\"",
            "id=\"radio-delete\"", "system-button-danger", "Exclusão indisponível para clientes rádio");
        assertThat(html).doesNotContain("Copiar todos", "radio-copy-all", "Identificação e status", "Métricas por coleta", "id=\"radio-summary-ap\"", "id=\"radio-summary-date\"",
            "id=\"radio-summary-connection\"", "id=\"radio-summary-interface\"");
        assertThat(html.split("id=\"radio-overview\"", -1)).hasSize(2);
        assertThat(html.split("id=\"radio-title\"", -1)).hasSize(2);
        assertThat(html).containsSubsequence("id=\"radio-collection-dialog\"", "form-dialog-frame",
            "id=\"radio-collection-close-top\"", "id=\"radio-collection-details\"",
            "app-modal-footer", "id=\"radio-collection-cancel\"");
        assertThat(html).doesNotContain("id=\"radio-collection-delete\"");
    }
    @Test void browserVisitPersistenceEndpointHasBeenRemoved() throws Exception {
        mvc.perform(post("/api/ap/ap/radio-visits/visit/close")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
            .andExpect(status().isNotFound());
        org.mockito.Mockito.verifyNoInteractions(clients);
    }
}
