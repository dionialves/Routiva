package br.com.scriptping.accesspoint;

import br.com.scriptping.equipment.EquipmentProvider;
import br.com.scriptping.zabbix.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AccessPointTests {
    AccessPointRepository repository;
    AccessPointService service;
    ZabbixService inventory;
    EquipmentProvider provider;
    MockMvc mvc;
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path temp;
    AccessPointSecrets secrets;
    AccessPointService.ConfigurationInput valid = new AccessPointService.ConfigurationInput("MikroTik", "reader", "private-secret", 8729, true);
    Host host = new Host("42", "AP Outdoor", "192.0.2.42", List.of());

    @BeforeEach void setup() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
        repository = new AccessPointRepository(new JdbcTemplate(source));
        inventory = mock(ZabbixService.class); provider = mock(EquipmentProvider.class);
        when(inventory.hosts()).thenReturn(List.of(host));
        when(provider.manufacturer()).thenReturn("MikroTik");
        when(provider.capabilities()).thenReturn(Set.of("overview", "wireless"));
        secrets = new AccessPointSecrets(temp.resolve("key").toString());
        service = new AccessPointService(repository, inventory, List.of(provider), secrets);
        mvc = MockMvcBuilders.standaloneSetup(new AccessPointController(service)).build();
    }
    @Test void validatesEntireSelectionAndPersistsWithoutDuplicates() {
        assertThrows(ResponseStatusException.class, () -> service.define(List.of("42", "unknown")));
        assertTrue(repository.all().isEmpty());
        service.define(List.of("42", "42")); service.define(List.of("42"));
        assertEquals(1, repository.all().size());
        assertNull(repository.all().getFirst().connectedClients());
        assertTrue(service.candidates().isEmpty());
        var restarted = new AccessPointService(repository, inventory, List.of(provider), secrets);
        assertEquals("42", restarted.list().getFirst().hostId());
    }
    @Test void inventoryChangesPreserveClassificationAndKnownReadings() {
        service.define(List.of("42"));
        repository.observation("42", "Radio", 0, 123);
        when(inventory.hosts()).thenReturn(List.of(new Host("42", "Novo nome", "192.0.2.43", List.of())));
        assertEquals("Novo nome", service.list().getFirst().name());
        when(inventory.hosts()).thenReturn(List.of());
        var row = service.list().getFirst();
        assertFalse(row.available()); assertEquals(0, row.connectedClients());
        assertEquals("Novo nome", service.pageHost("42").name());
        assertThrows(ResponseStatusException.class, () -> service.read("42", "wireless", null));
    }
    @Test void exposesCurrentInventoryGroupsWithoutInventingGroupsForMissingHosts() {
        var groups = List.of(new Host.Group("outdoor", "Outdoor"));
        when(inventory.hosts()).thenReturn(List.of(new Host("42", "AP Outdoor", "192.0.2.42", groups)));
        service.define(List.of("42"));
        assertEquals(groups, service.list().getFirst().groups());
        when(inventory.hosts()).thenReturn(List.of());
        assertTrue(service.list().getFirst().groups().isEmpty());
        assertFalse(service.list().getFirst().available());
    }
    @Test void onlyValidProviderReadingsUpdateClientCounts() {
        service.define(List.of("42"));
        service.save("42", valid);
        when(provider.read(host, "wireless", null)).thenReturn(new EquipmentProvider.Snapshot(123, List.of(Map.of("mac-address", "AA")), ""));
        service.read("42", "wireless", null);
        assertEquals(1, repository.all().getFirst().connectedClients());
        when(provider.read(host, "wireless", null)).thenReturn(new EquipmentProvider.Snapshot(124, List.of(), "Indisponível"));
        service.read("42", "wireless", null);
        assertEquals(1, repository.all().getFirst().connectedClients());
        assertEquals(123L, repository.all().getFirst().lastCollectedAt());
    }
    @Test void newRoutesRequireClassificationAndOldPageIsRemoved() throws Exception {
        mvc.perform(get("/ap/42")).andExpect(status().isNotFound());
        mvc.perform(post("/api/ap").contentType("application/json").content("{\"hostIds\":[\"42\"]}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].hostId").value("42"));
        mvc.perform(get("/ap/42")).andExpect(status().isOk()).andExpect(view().name("host"));
        mvc.perform(get("/hosts/42")).andExpect(status().isNotFound());
        mvc.perform(get("/api/ap")).andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test void importsPendingAndBlocksCollectionWithoutContactingEquipment() {
        service.define(List.of("42"));
        assertEquals("PENDING", service.list().getFirst().status());
        assertNull(service.configuration("42").manufacturer());
        assertThrows(ResponseStatusException.class, () -> service.read("42", "overview", null));
        verify(provider, never()).read(any(), any(), any());
    }
    @Test void savingTestsExactCredentialsAndOnlyActivatesOnSuccess() {
        service.define(List.of("42"));
        var saved = service.save("42", valid);
        assertEquals("ACTIVE", saved.status());
        verify(provider).testConnection(eq(host), eq(new EquipmentProvider.Connection("reader", "private-secret", 8729)));
        assertNotEquals("private-secret", repository.configuration("42").encryptedPassword());
        assertEquals("private-secret", secrets.decrypt(repository.configuration("42").encryptedPassword()));
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.BAD_GATEWAY, "Autenticação recusada."))
            .when(provider).testConnection(any(), any());
        saved = service.save("42", new AccessPointService.ConfigurationInput("MikroTik", "other", "wrong", 1234, true));
        assertEquals("CONNECTION_FAILED", saved.status());
        assertTrue(saved.enabled());
        assertEquals("other", saved.login());
        assertEquals("Autenticação recusada.", saved.message());
        assertThrows(ResponseStatusException.class, () -> service.read("42", "wireless", null));
    }
    @Test void testAloneDoesNotSaveOrActivateAndSavingRetests() {
        service.define(List.of("42"));
        assertTrue(service.test("42", valid).success());
        assertEquals("PENDING", service.configuration("42").status());
        assertFalse(service.configuration("42").passwordConfigured());
        service.save("42", valid);
        verify(provider, times(2)).testConnection(any(), any());
    }
    @Test void inactiveDoesNotConnectAndBlankPasswordPreservesSavedSecret() {
        service.define(List.of("42"));
        service.save("42", valid);
        clearInvocations(provider);
        var saved = service.save("42", new AccessPointService.ConfigurationInput("MikroTik", "reader", "", 8729, false));
        assertEquals("INACTIVE", saved.status());
        assertEquals("private-secret", secrets.decrypt(repository.configuration("42").encryptedPassword()));
        verify(provider, never()).testConnection(any(), any());
        assertThrows(ResponseStatusException.class, () -> new AccessPointConnections(repository, secrets).require(host));
    }
    @Test void equipmentCredentialsAreIsolatedAndIpChangeRequiresRevalidation() {
        var second = new Host("43", "Other AP", "192.0.2.43", List.of());
        when(inventory.hosts()).thenReturn(List.of(host, second));
        service.define(List.of("42", "43")); service.save("42", valid);
        service.save("43", new AccessPointService.ConfigurationInput("MikroTik", "other", "other-secret", 1234, true, true));
        var resolver = new AccessPointConnections(repository, secrets);
        assertEquals("private-secret", resolver.require(host).password());
        assertEquals("other-secret", resolver.require(second).password());
        assertEquals(1234, resolver.require(second).port());
        assertTrue(resolver.require(second).tls());
        assertFalse(resolver.require(host).tls());
        var changed = new Host("42", "Renamed", "192.0.2.99", List.of());
        when(inventory.hosts()).thenReturn(List.of(changed, second));
        assertThrows(ResponseStatusException.class, () -> resolver.require(changed));
        assertEquals("CONNECTION_FAILED", service.list().stream().filter(r -> r.hostId().equals("42")).findFirst().orElseThrow().status());
        assertEquals("reader", service.configuration("42").login());
    }
    @Test void validatesFieldsAndDeletesOnlyLocalApAllowingReimport() throws Exception {
        service.define(List.of("42"));
        assertThrows(ResponseStatusException.class, () -> service.save("42", new AccessPointService.ConfigurationInput("Ubiquiti", "a", "b", 8729, true)));
        assertThrows(ResponseStatusException.class, () -> service.save("42", new AccessPointService.ConfigurationInput("MikroTik", "a", "b", 65536, true)));
        assertThrows(ResponseStatusException.class, () -> service.save("42", new AccessPointService.ConfigurationInput("MikroTik", "a", "", 8729, true)));
        service.save("42", valid);
        mvc.perform(get("/api/ap/42/configuration")).andExpect(status().isOk())
            .andExpect(jsonPath("$.password").doesNotExist()).andExpect(jsonPath("$.encryptedPassword").doesNotExist())
            .andExpect(jsonPath("$.passwordConfigured").value(true));
        when(inventory.hosts()).thenReturn(List.of());
        mvc.perform(delete("/api/ap/42")).andExpect(status().isOk());
        assertTrue(repository.all().isEmpty());
        when(inventory.hosts()).thenReturn(List.of(host));
        assertEquals(List.of(host), service.candidates());
        verify(inventory, atLeastOnce()).hosts(); verifyNoMoreInteractions(inventory);
        service.define(List.of("42"));
        assertEquals("PENDING", service.configuration("42").status());
        assertFalse(service.configuration("42").passwordConfigured());
    }

    @Test void configurationApiSavesAndTestsWithoutReturningPasswords() throws Exception {
        service.define(List.of("42"));
        String body = "{\"manufacturer\":\"MikroTik\",\"login\":\"reader\",\"password\":\"private-secret\",\"port\":8729,\"enabled\":true}";
        mvc.perform(post("/api/ap/42/configuration/test").contentType("application/json").content(body))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        assertEquals("PENDING", service.configuration("42").status());
        mvc.perform(put("/api/ap/42/configuration").contentType("application/json").content(body))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"))
            .andExpect(jsonPath("$.password").doesNotExist());
        mvc.perform(get("/api/ap")).andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-secret"))));
    }
}
