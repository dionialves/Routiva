package br.com.scriptping;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpSession;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:zabbix-settings;DB_CLOSE_DELAY=-1", "spring.datasource.username=sa", "spring.datasource.password=", "zabbix.url=", "zabbix.username=", "zabbix.password=", "collector.enabled=false"})
@AutoConfigureMockMvc
class ZabbixSettingsTests {
    @Autowired MockMvc mvc;
    @Autowired ZabbixSettings settings;
    @Autowired MonitorService monitor;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper mapper;
    @MockitoBean ZabbixClient client;
    @MockitoBean CollectorClient collector;

    @Test void completeFlowPersistsSelectionsAndOnlyCollectsAuthorizedHosts() throws Exception {
        var a = new Host.Group("a", "Produção");
        var b = new Host.Group("b", "Outros");
        var shared = new Host("1", "Compartilhado", "10.0.0.1", List.of(a, b));
        var denied = new Host("2", "Bloqueado", "10.0.0.2", List.of(b));
        when(client.configured()).thenReturn(true);
        when(client.groups()).thenReturn(List.of(a, b));
        when(client.discover(Set.of("a"))).thenReturn(List.of(shared));
        when(client.fetchSelected(Set.of("a"), Set.of("1"))).thenReturn(List.of(shared, denied));
        var session = new MockHttpSession();
        mvc.perform(get("/configuracao/zabbix").session(session)).andExpect(status().isOk());
        String csrf = (String) session.getAttribute("zabbixCsrf");
        mvc.perform(post("/configuracao/zabbix/conexao").session(session).param("csrf", csrf)
            .param("url", "https://example.test/api_jsonrpc.php").param("username", "api").param("password", "secret-value"))
            .andExpect(status().is3xxRedirection());
        mvc.perform(post("/configuracao/zabbix/grupos").session(session).param("csrf", csrf).param("ids", "a"))
            .andExpect(redirectedUrl("/configuracao/zabbix/hosts"));
        mvc.perform(get("/configuracao/zabbix/hosts").session(session)).andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Grupos e hosts")));
        mvc.perform(post("/configuracao/zabbix/hosts").session(session).param("csrf", csrf).param("ids", "1"))
            .andExpect(status().is3xxRedirection());
        verify(client, never()).fetchSelected(anySet(), anySet());
        mvc.perform(post("/configuracao/zabbix/sincronizar").session(session).param("csrf", csrf)).andExpect(status().is3xxRedirection());
        assertThat(monitor.hosts()).extracting(Host::id).containsExactly("1");
        assertThat(monitor.hosts().getFirst().groups()).containsExactly(a);
        var restored = new ZabbixSettings(jdbc, mapper, "", "", "");
        assertThat(restored.current().hosts()).containsExactly("1");
        assertThat(restored.current().inventory()).isEqualTo(monitor.hosts());
        mvc.perform(get("/configuracao/zabbix").session(session)).andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret-value"))));
        when(client.fetchSelected(Set.of("a"), Set.of("1"))).thenThrow(new IllegalStateException("Falha"));
        assertThatThrownBy(monitor::sync).isInstanceOf(IllegalStateException.class);
        assertThat(monitor.hosts()).hasSize(1);
        monitor.selectHosts(Set.of());
        assertThat(monitor.hosts()).isEmpty();
        verify(collector, atLeastOnce()).synchronize(List.of());
        assertThatThrownBy(() -> monitor.selectHosts(Set.of("2"))).isInstanceOf(IllegalArgumentException.class);
        mvc.perform(post("/configuracao/zabbix/sincronizar").session(session).param("csrf", "invalid"))
            .andExpect(status().isForbidden());
    }

    @Test void unifiedInventoryAndSelectionValidateBeforeSaving() throws Exception {
        var group = new Host.Group("g", "Rede");
        var host = new Host("h", "Roteador", "10.0.0.1", List.of(group));
        when(client.groups()).thenReturn(List.of(group));
        when(client.discover(Set.of("g"))).thenReturn(List.of(host));
        var session = new MockHttpSession();
        mvc.perform(get("/configuracao/zabbix").session(session)).andExpect(status().isOk());
        String csrf = (String) session.getAttribute("zabbixCsrf");
        mvc.perform(get("/configuracao/zabbix/inventario")).andExpect(status().isOk())
            .andExpect(jsonPath("$.hosts[0].name").value("Roteador"));
        mvc.perform(post("/configuracao/zabbix/api/selecao").session(session).param("csrf", csrf)
            .param("groups", "g").param("hosts", "h")).andExpect(status().isOk());
        assertThat(settings.current().hosts()).containsExactly("h");
        mvc.perform(post("/configuracao/zabbix/api/selecao").session(session).param("csrf", csrf)
            .param("groups", "g").param("hosts", "invalid")).andExpect(status().isBadRequest());
        assertThat(settings.current().hosts()).containsExactly("h");
        mvc.perform(post("/configuracao/zabbix/api/selecao").session(session).param("csrf", "invalid"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/configuracao/zabbix/api/selecao").session(session).param("csrf", csrf))
            .andExpect(status().isOk());
        assertThat(settings.current().hosts()).isEmpty();
    }

    @Test void synchronizationHasNoScheduledTrigger() throws Exception {
        assertThat(MonitorService.class.getMethod("sync").getAnnotation(org.springframework.scheduling.annotation.Scheduled.class)).isNull();
    }
}
