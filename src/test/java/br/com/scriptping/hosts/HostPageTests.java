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
    @MockitoBean HostCatalog catalog;
    @MockitoBean HostAuthentication authentication;
    @MockitoBean br.com.scriptping.zabbix.ZabbixService inventory;
    @Test void deletionRequiresExplicitConfirmation() throws Exception {
        mvc.perform(delete("/api/host-configurations/42").with(csrf()).contentType("application/json")
            .content("{\"revision\":\"version\",\"confirmed\":false}"))
            .andExpect(status().isBadRequest());
        verify(catalog, never()).delete(anyString(), any());
        mvc.perform(delete("/api/host-configurations/42").with(csrf()).contentType("application/json")
            .content("{\"revision\":\"version\",\"confirmed\":true}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").value("42"));
        verify(catalog).delete("42", "version");
    }
    @Test void listIncludesLocalConnectionStatusWithoutExposingCredentials() throws Exception {
        var host = new ManagedHost("42", "router", "Router", java.util.List.of(), java.util.List.of(), "5", "192.0.2.42", "", true, "161", 2, "public", true, "r", true, 1, 1, "available");
        when(catalog.all()).thenReturn(java.util.List.of(host));
        when(authentication.connectionStatuses(java.util.List.of(host))).thenReturn(java.util.Map.of("42", "ACTIVE"));
        mvc.perform(get("/api/host-configurations")).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value("42"))
            .andExpect(jsonPath("$[0].hostname").value("router"))
            .andExpect(jsonPath("$[0].connectionStatus").value("ACTIVE"))
            .andExpect(jsonPath("$[0].encryptedPassword").doesNotExist());
        verifyNoInteractions(inventory);
    }
    @Test void rendersSharedFormAndPickers() throws Exception {
        var result = mvc.perform(get("/configuracao/hosts")).andExpect(status().isOk())
            .andExpect(content().string(containsString("id=\"incident-host-group-picker\"")))
            .andExpect(content().string(containsString("id=\"incident-host-template-picker\"")))
            .andExpect(content().string(containsString("form-dialog-frame")))
            .andExpect(content().string(containsString("Salvar no Zabbix")))
            .andExpect(content().string(containsString("host-auth-tab")))
            .andExpect(content().string(containsString("host-filter-connection")))
            .andExpect(content().string(containsString("data-host-sort=\"connection\"")))
            .andReturn();
        String preview = System.getProperty("host.preview.output");
        if (preview != null) java.nio.file.Files.writeString(java.nio.file.Path.of(preview), result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }
}
