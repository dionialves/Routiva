package br.com.scriptping.maps;

import br.com.scriptping.equipment.EquipmentProvider;
import br.com.scriptping.mikrotik.MikrotikService;
import br.com.scriptping.zabbix.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@JdbcTest
@Import(MappedConnectionController.class)
class MappedConnectionTests {
    @Autowired MappedConnectionController controller;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @MockitoBean ZabbixService inventory;
    @MockitoBean MapZabbixReadings zabbixReadings;
    @MockitoBean MikrotikService mikrotik;
    @MockitoBean br.com.scriptping.datacom.DatacomEquipmentProvider datacom;
    @MockitoBean br.com.scriptping.mimosa.MimosaEquipmentProvider mimosa;
    @MockitoBean br.com.scriptping.cisco.CiscoEquipmentProvider cisco;
    @MockitoBean br.com.scriptping.hosts.HostConnections connections;
    @MockitoBean br.com.scriptping.hosts.HostRepository hosts;

    @org.junit.jupiter.api.BeforeEach
    void freshReadingCache() {
        controller = new MappedConnectionController(jdbc, hosts, zabbixReadings);
    }

    @Test void discoversAndListsZabbixInterfacesWithoutEquipmentAuthentication() {
        var rows = List.of(Map.of("name", "eth1_emac1", "source", "zabbix"), Map.of("name", "wifi0", "source", "zabbix"));
        when(zabbixReadings.discover("a")).thenReturn(rows);
        assertThat(controller.discover("a")).isEqualTo(rows);
        assertThat(controller.interfaces("a")).isEqualTo(rows);
        verify(hosts, times(2)).target("a");
        verifyNoInteractions(mikrotik, datacom, mimosa, cisco, connections, inventory);
        verify(hosts, never()).credentials(anyString());
    }
    @Test void groupInterfacePickerIncludesLogicalInterfacesOnlyWhenRequested() {
        var rows = List.of(Map.of("name", "vlan632", "source", "zabbix"));
        when(zabbixReadings.discover("a", true)).thenReturn(rows);
        assertThat(controller.discoverForPicker("a", true)).isEqualTo(rows);
        verify(hosts).target("a");
        verify(zabbixReadings).discover("a", true);
        verifyNoInteractions(mikrotik, datacom, mimosa, cisco, connections, inventory);
    }
    @Test void validatesDraftShapeWithoutQueryingZabbixOrPersistingConnections() {
        var saved = controller.validate(new MappedConnectionController.Connection(null, "Cabo A-B", "a", "eth1_emac1", "b", "wifi0"));
        assertThat(saved.id()).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM network_map", Integer.class)).isZero();
        assertThatThrownBy(() -> controller.validate(new MappedConnectionController.Connection(null, "Inválida", "a", "", "b", "wifi0")))
            .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(zabbixReadings, mikrotik, datacom, mimosa, cisco, connections);
    }
    @Test void refusesUnknownLocalHostBeforeQueryingZabbix() {
        when(hosts.target("missing")).thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> controller.discover("missing")).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(zabbixReadings, mikrotik, connections);
    }

    @Test void readsOnlyPersistedConnectionsInOneZabbixBatchWithoutEquipmentCredentials() {
        var c = new MappedConnectionController.Connection("link", "Rede", "a", "ether1", "b", "ether2");
        persist(c);
        var keys = Set.of(new MapZabbixReadings.Key("a", "ether1"), new MapZabbixReadings.Key("b", "ether2"));
        var endpoint = new MappedConnectionController.Endpoint(1000, "ether1", true, "1", "2", null, null,
            181000, 181000, 181000, null, 1000, null);
        when(zabbixReadings.read(keys, false)).thenReturn(Map.of(new MapZabbixReadings.Key("a", "ether1"), endpoint,
            new MapZabbixReadings.Key("b", "ether2"), endpoint));
        assertThat(controller.readings("map", Set.of("link", "untrusted"), false)).hasSize(1);
        verify(zabbixReadings).read(keys, false);
        var batch = controller.snapshot("map", Set.of("link"), false, "");
        assertThat(batch.unchanged()).isFalse(); assertThat(batch.readings()).hasSize(1);
        var same = controller.snapshot("map", Set.of("link"), false, batch.version());
        assertThat(same.unchanged()).isTrue(); assertThat(same.readings()).isEmpty();
        verifyNoInteractions(mikrotik, datacom, mimosa, cisco, connections, hosts);
        when(zabbixReadings.read(keys, true)).thenReturn(Map.of());
        controller.readings("map", Set.of("link"), true);
        verify(zabbixReadings).read(keys, true);
        assertThatThrownBy(() -> controller.readings("missing", Set.of("link"), false)).isInstanceOf(ResponseStatusException.class);
    }
    void persist(MappedConnectionController.Connection connection) {
        try {
            var json = new com.fasterxml.jackson.databind.ObjectMapper();
            var link = Map.of("from", "a", "to", "b", "mappedConnectionId", connection.id(), "connection", connection);
            jdbc.update("INSERT INTO network_map(id,revision,layout) VALUES ('map',1,?)",
                json.writeValueAsString(Map.of("name", "Rede", "nodes", List.of(), "links", List.of(link))));
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }
}
