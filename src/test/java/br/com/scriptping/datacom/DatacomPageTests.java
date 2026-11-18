package br.com.scriptping.datacom;

import br.com.scriptping.hosts.HostRepository;
import br.com.scriptping.zabbix.Host;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DatacomController.class)
@WithMockUser(roles = "USER")
class DatacomPageTests {
    @Autowired MockMvc mvc;
    @MockitoBean DatacomEquipmentProvider provider;
    @MockitoBean HostRepository hosts;
    @Test void rendersWiredEquipmentOutsideAccessPointsAndListsOnlyIds() throws Exception {
        var credential = new HostRepository.Credentials("Datacom", "reader", "encrypted-secret", 22, true, "ACTIVE", "", "192.0.2.1", false, 1L);
        when(hosts.target("42")).thenReturn(new Host("42", "OLT <teste>", "192.0.2.1", List.of()));
        when(hosts.credentials("42")).thenReturn(credential);
        when(hosts.credentials()).thenReturn(Map.of("42", credential));
        mvc.perform(get("/equipamentos/42")).andExpect(status().isOk())
            .andExpect(content().string(containsString("OLT &lt;teste&gt;")))
            .andExpect(content().string(containsString("id=\"datacom-interfaces\"")))
            .andExpect(content().string(containsString("query-filter-field")))
            .andExpect(content().string(not(containsString("data-tab=\"wireless\""))))
            .andExpect(content().string(not(containsString("encrypted-secret"))));
        mvc.perform(get("/api/equipment/datacom")).andExpect(status().isOk())
            .andExpect(content().json("[\"42\"]")).andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get("/api/hosts/42/datacom/wireless")).andExpect(status().isNotFound());
    }
}
