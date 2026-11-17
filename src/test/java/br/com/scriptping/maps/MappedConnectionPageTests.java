package br.com.scriptping.maps;

import br.com.scriptping.accesspoint.AccessPointRepository;
import br.com.scriptping.mikrotik.MikrotikService;
import br.com.scriptping.zabbix.ZabbixService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.containsString;

@WebMvcTest(MappedConnectionController.class)
@org.springframework.security.test.context.support.WithMockUser(roles = "USER")
class MappedConnectionPageTests {
    @Autowired MockMvc mvc;
    @MockitoBean JdbcTemplate jdbc;
    @MockitoBean ZabbixService inventory;
    @MockitoBean MikrotikService mikrotik;
    @MockitoBean AccessPointRepository equipment;
    @Test void rendersStandaloneTableWithSharedShellAndFilters() throws Exception {
        mvc.perform(get("/conexoes")).andExpect(status().isOk())
            .andExpect(content().string(containsString("Conexões mapeadas")))
            .andExpect(content().string(containsString("connection-rows")))
            .andExpect(content().string(containsString("query-filter-field")))
            .andExpect(header().string("Cache-Control", "no-store"));
    }
}
