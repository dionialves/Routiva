package br.com.scriptping.mikrotik;

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

@WebMvcTest(MikrotikController.class)
class MikrotikPageTests {
    @Autowired MockMvc mvc;
    @MockitoBean ZabbixService inventory;
    @MockitoBean MikrotikService mikrotik;

    @Test void rendersHostAndSharedNavigationWithoutExposingCredentials() throws Exception {
        when(inventory.hosts()).thenReturn(List.of(new Host("42", "MikroTik São Paulo", "192.0.2.1", List.of())));
        mvc.perform(get("/hosts/42")).andExpect(status().isOk())
            .andExpect(content().string(containsString("MikroTik São Paulo")))
            .andExpect(content().string(containsString("data-host-id=\"42\"")))
            .andExpect(content().string(containsString("Wireless")));
        verifyNoInteractions(mikrotik);
    }
}
