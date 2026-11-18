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
    @MockitoBean br.com.scriptping.hosts.HostConnections connections;
    @MockitoBean br.com.scriptping.hosts.HostRepository hosts;
    @Test void standaloneConnectionPageHasBeenRemoved() throws Exception {
        mvc.perform(get("/conexoes")).andExpect(status().isNotFound());
    }
}
