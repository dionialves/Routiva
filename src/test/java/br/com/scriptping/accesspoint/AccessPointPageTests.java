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

@WebMvcTest(AccessPointController.class)
@org.springframework.security.test.context.support.WithMockUser(roles = "ADMIN")
class AccessPointPageTests {
    @Autowired MockMvc mvc;
    @MockitoBean AccessPointService service;

    @Test void rendersHostAndSharedNavigationWithoutExposingCredentials() throws Exception {
        when(service.pageHost("42")).thenReturn(new Host("42", "MikroTik São Paulo", "192.0.2.1", List.of()));
        mvc.perform(get("/ap/42")).andExpect(status().isOk())
            .andExpect(content().string(containsString("MikroTik São Paulo")))
            .andExpect(content().string(containsString("data-host-id=\"42\"")))
            .andExpect(content().string(containsString("Wireless")))
            .andExpect(content().string(containsString("connected-clients")))
            .andExpect(content().string(containsString("signal-distribution")))
            .andExpect(content().string(containsString("Tempo conectado")))
            .andExpect(content().string(not(containsString("interface-search"))))
            .andExpect(content().string(not(containsString("wireless-search"))))
            .andExpect(content().string(not(containsString("filters-toggle"))));
        mvc.perform(get("/hosts/42")).andExpect(status().isNotFound());
        mvc.perform(get("/ap")).andExpect(status().isOk())
            .andExpect(content().string(containsString("Hosts disponíveis")))
            .andExpect(content().string(containsString("Definir como AP")));
    }
}
