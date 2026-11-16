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
    Host host = new Host("42", "AP Outdoor", "192.0.2.42", List.of());

    @BeforeEach void setup() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
        repository = new AccessPointRepository(new JdbcTemplate(source));
        inventory = mock(ZabbixService.class); provider = mock(EquipmentProvider.class);
        when(inventory.hosts()).thenReturn(List.of(host));
        when(provider.manufacturer()).thenReturn("MikroTik");
        when(provider.capabilities()).thenReturn(Set.of("overview", "wireless"));
        service = new AccessPointService(repository, inventory, List.of(provider));
        mvc = MockMvcBuilders.standaloneSetup(new AccessPointController(service)).build();
    }
    @Test void validatesEntireSelectionAndPersistsWithoutDuplicates() {
        assertThrows(ResponseStatusException.class, () -> service.define(List.of("42", "unknown")));
        assertTrue(repository.all().isEmpty());
        service.define(List.of("42", "42")); service.define(List.of("42"));
        assertEquals(1, repository.all().size());
        assertNull(repository.all().getFirst().connectedClients());
        assertTrue(service.candidates().isEmpty());
        var restarted = new AccessPointService(repository, inventory, List.of(provider));
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
}
