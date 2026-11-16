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
    @MockitoBean RadioVisitService visits;
    @Test void rendersSharedTableModalAndAccessibleTabsExactlyOnce() throws Exception {
        String html = mvc.perform(get("/clientes/radio")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(html).contains("Clientes Rádio", "hosts-table", "form-dialog", "role=\"tablist\"", "aria-controls=\"radio-history\"", "Vincular login");
        assertThat(html.split("id=\"radio-overview\"", -1)).hasSize(2);
        assertThat(html.split("id=\"radio-title\"", -1)).hasSize(2);
    }
    @Test void closingReturnsAcceptedAndOnlyEnqueuesTheVisit() throws Exception {
        org.mockito.Mockito.when(clients.visibleHosts()).thenReturn(java.util.Set.of("ap"));
        var owner = new org.springframework.mock.web.MockHttpSession();
        mvc.perform(post("/api/ap/ap/radio-visits/visit/close").session(owner)
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
            .andExpect(status().isAccepted());
        org.mockito.Mockito.verify(visits).close("visit", "ap", owner.getId());
        org.mockito.Mockito.verify(clients, org.mockito.Mockito.never()).persistVisit(org.mockito.ArgumentMatchers.any());
    }

}
