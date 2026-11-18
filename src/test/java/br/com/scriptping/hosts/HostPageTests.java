package br.com.scriptping.hosts;

import br.com.scriptping.zabbix.ZabbixClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(HostController.class)
@WithMockUser(roles = "ADMIN")
class HostPageTests {
    @Autowired MockMvc mvc;
    @MockitoBean ZabbixClient zabbix;
    @Test void deletionRequiresExplicitConfirmation() throws Exception {
        mvc.perform(delete("/api/host-configurations/42").with(csrf()).contentType("application/json")
            .content("{\"revision\":\"version\",\"confirmed\":false}"))
            .andExpect(status().isBadRequest());
        verify(zabbix, never()).deleteManagedHost(anyString(), any());
        mvc.perform(delete("/api/host-configurations/42").with(csrf()).contentType("application/json")
            .content("{\"revision\":\"version\",\"confirmed\":true}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").value("42"));
        verify(zabbix).deleteManagedHost("42", "version");
    }
    @Test void rendersSharedFormAndPickers() throws Exception {
        mvc.perform(get("/configuracao/hosts")).andExpect(status().isOk())
            .andExpect(content().string(containsString("id=\"incident-host-group-picker\"")))
            .andExpect(content().string(containsString("id=\"incident-host-template-picker\"")))
            .andExpect(content().string(containsString("form-dialog-frame")))
            .andExpect(content().string(containsString("Salvar no Zabbix")));
    }
}
