package br.com.scriptping.zabbix;

import br.com.scriptping.ingestion.CollectorClient;

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
    @Autowired ZabbixService monitor;
    @Autowired ZabbixSettingsRepository repository;
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
        mvc.perform(get("/configuracao/zabbix/hosts").session(session)).andExpect(status().isOk());
        mvc.perform(post("/configuracao/zabbix/hosts").session(session).param("csrf", csrf).param("ids", "1"))
            .andExpect(status().is3xxRedirection());
        verify(client, never()).fetchSelected(anySet(), anySet());
        mvc.perform(post("/configuracao/zabbix/sincronizar").session(session).param("csrf", csrf)).andExpect(status().is3xxRedirection());
        assertThat(monitor.hosts()).extracting(Host::id).containsExactly("1");
        assertThat(monitor.hosts().getFirst().groups()).containsExactly(a);
        var restored = new ZabbixSettings(repository, mapper, "", "", "");
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
        when(client.fetchSelected(Set.of("g"), Set.of("h"))).thenReturn(List.of(host));
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

    @Test void jsonImportValidatesCsrfAndConfirmsPersistedHosts() throws Exception {
        var g = new Host.Group("json", "JSON");
        var h = new Host("json-host", "Host", "10.1.1.1", List.of(g));
        when(client.groups()).thenReturn(List.of(g));
        when(client.discover(Set.of("json"))).thenReturn(List.of(h));
        when(client.fetchSelected(Set.of("json"), Set.of("json-host"))).thenReturn(List.of(h));
        var session = new MockHttpSession();
        mvc.perform(get("/configuracao/zabbix").session(session)).andExpect(status().isOk());
        mvc.perform(post("/configuracao/zabbix/api/importar").session(session)
            .header("X-Zabbix-CSRF", "wrong").contentType("application/json")
            .content("{\"hosts\":[\"json-host\"]}")).andExpect(status().isForbidden());
        mvc.perform(post("/configuracao/zabbix/api/importar").session(session)
            .header("X-Zabbix-CSRF", session.getAttribute("zabbixCsrf")).contentType("application/json")
            .content("{\"hosts\":[\"json-host\"]}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.result.state").value("completed"))
            .andExpect(jsonPath("$.imported[?(@.id == 'json-host')]").isNotEmpty());
        var restored = new ZabbixSettings(repository, mapper, "", "", "");
        assertThat(restored.current().hosts()).containsExactly("json-host");
        assertThat(restored.current().inventory()).containsExactly(h);
        when(client.groups()).thenThrow(new IllegalStateException("offline"));
        mvc.perform(get("/configuracao/zabbix/resultado").session(session))
            .andExpect(status().isOk()).andExpect(jsonPath("$.result.selectionSaved").value(true))
            .andExpect(jsonPath("$.result.entries[0].name").value("Host"))
            .andExpect(jsonPath("$.result.entries[0].ip").value("10.1.1.1"))
            .andExpect(jsonPath("$.result.entries[0].outcome").value("imported"))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret"))));
    }

    @Test void everyPageUsesOneSharedMobileNavigation() throws Exception {
        for (String route : List.of("/disponibilidade", "/mapas", "/configuracao/zabbix")) {
            String html = mvc.perform(get(route)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
            assertThat(html.split("id=\"mobile-nav-toggle\"", -1)).hasSize(2);
            assertThat(html).doesNotContain("id=\"mobile-menu\"", "id=\"menu-backdrop\"");
            assertThat(html).contains("aria-controls=\"settings-submenu\"", "/icons.svg#settings");
        }
    }

    @Test void synchronizationHasNoScheduledTrigger() throws Exception {
        assertThat(ZabbixService.class.getMethod("sync").getAnnotation(org.springframework.scheduling.annotation.Scheduled.class)).isNull();
    }
}
