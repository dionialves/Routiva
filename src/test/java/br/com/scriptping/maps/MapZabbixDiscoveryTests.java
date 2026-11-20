package br.com.scriptping.maps;

import br.com.scriptping.zabbix.ZabbixClient;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MapZabbixDiscoveryTests {
    final ZabbixClient client = mock(ZabbixClient.class);
    final AtomicLong clock = new AtomicLong(1_000_000);
    final MapZabbixReadings service = new MapZabbixReadings(client, clock::get);
    final ObjectMapper json = new ObjectMapper();
    @AfterEach void close() { service.close(); }
    JsonNode item(String id, String name, String metric, String oid, int index) {
        return json.valueToTree(Map.of("itemid", id, "hostid", "1", "name", "Interface " + name + "(Uplink): metric",
            "key_", "net.if." + metric + "[" + oid + "." + index + "]", "units", metric.equals("speed") ? "bps" : "", "delay", "60s"));
    }
    JsonNode value(String id, String value) {
        return json.valueToTree(Map.of("itemid", id, "lastvalue", value, "lastclock", "1000", "state", "0", "status", "0"));
    }
    @Test void ordersInterfaceNumbersNaturallyIncludingChassisSlotAndPort() {
        var names = new ArrayList<>(List.of("ether8", "ether24", "ether10", "ether2", "ether1"));
        names.sort(MapZabbixReadings::compareInterfaceNames);
        assertThat(names).containsExactly("ether1", "ether2", "ether8", "ether10", "ether24");
        var ports = new ArrayList<>(List.of("ten-gigabit-ethernet-1/10/1", "ten-gigabit-ethernet-1/2/10", "ten-gigabit-ethernet-1/2/2"));
        ports.sort(MapZabbixReadings::compareInterfaceNames);
        assertThat(ports).containsExactly("ten-gigabit-ethernet-1/2/2", "ten-gigabit-ethernet-1/2/10", "ten-gigabit-ethernet-1/10/1");
    }
    @Test void discoversMimosaNamesAndSharesTheCatalogueWithReadingsWithoutStartingPolling() {
        when(client.mapInterfaceItems(Set.of("1"))).thenReturn(List.of(
            item("1", "eth1_emac1", "status", "ifOperStatus", 1), item("2", "wifi0", "status", "ifOperStatus", 2),
            item("3", "wlan0", "status", "ifOperStatus", 3), item("4", "wifi0", "type", "ifType", 2),
            item("5", "eth1_emac1", "speed", "ifHighSpeed", 1)));
        when(client.mapItemValues(anySet())).thenReturn(List.of(value("1", "1"), value("2", "1"), value("3", "2"), value("4", "71"), value("5", "1000000000")));
        var rows = service.discover("1");
        assertThat(rows).extracting(row -> row.get("name")).containsExactly("eth1_emac1", "wifi0", "wlan0");
        assertThat(rows.get(0)).containsEntry("source", "zabbix").containsEntry("speed", "1000000000bps");
        assertThat(rows.get(1)).containsEntry("type", "wireless").containsEntry("snmp-type", "71");
        assertThat(rows.get(2)).containsEntry("running", "false");
        assertThat(service.discover("1")).isEqualTo(rows);
        service.tick();
        verify(client).mapItemValues(Set.of("1", "2", "3", "4", "5"));
        var key = new MapZabbixReadings.Key("1", rows.getFirst().get("name"));
        assertThat(service.read(Set.of(key), false).get(key).up()).isTrue();
        verify(client).mapInterfaceItems(Set.of("1"));
    }
    @Test void usesExactTagsAndFiltersKnownVirtualInterfaces() {
        var physical = item("1", "Label", "status", "ifOperStatus", 1);
        ((com.fasterxml.jackson.databind.node.ObjectNode) physical).set("tags", json.valueToTree(List.of(
            Map.of("tag", "ifname", "value", "sfp-sfpplus1"), Map.of("tag", "connector", "value", "sfpplus"))));
        when(client.mapInterfaceItems(anySet())).thenReturn(List.of(physical,
            item("2", "vlan10", "status", "ifOperStatus", 2), item("3", "vlan10", "type", "ifType", 2)));
        when(client.mapItemValues(anySet())).thenReturn(List.of(value("1", "1"), value("2", "1"), value("3", "135")));
        var rows = service.discover("1");
        assertThat(rows).hasSize(1);
        assertThat(service.discover("1", true)).extracting(row -> row.get("name"))
            .containsExactly("sfp-sfpplus1", "vlan10");
        assertThat(service.discover("1")).hasSize(1);
        verify(client, times(1)).mapInterfaceItems(anySet());
        assertThat(rows.getFirst()).containsEntry("name", "sfp-sfpplus1").containsEntry("connector", "sfpplus");
        var key = new MapZabbixReadings.Key("1", "sfp-sfpplus1");
        assertThat(service.read(Set.of(key), false).get(key).error()).isNull();
    }
    @Test void reportsAmbiguousNamesAndZabbixFailuresWithoutReturningInventedPorts() {
        when(client.mapInterfaceItems(anySet())).thenReturn(List.of(item("1", "eth0", "status", "ifOperStatus", 1), item("2", "eth0", "status", "ifOperStatus", 2)));
        when(client.mapItemValues(anySet())).thenReturn(List.of());
        assertThatThrownBy(() -> service.discover("1")).hasMessageContaining("nomes duplicados");
        reset(client);
        when(client.mapInterfaceItems(anySet())).thenThrow(new IllegalStateException());
        assertThatThrownBy(() -> service.discover("2")).hasMessageContaining("Zabbix");
    }
    @Test void refreshesOneShotDiscoveryAfterAMinuteAndNeverTurnsOldStateIntoDown() {
        when(client.mapInterfaceItems(anySet())).thenReturn(List.of(item("1", "eth0", "status", "ifOperStatus", 1)));
        when(client.mapItemValues(anySet())).thenReturn(List.of(value("1", "2")));
        assertThat(service.discover("1").getFirst()).containsEntry("running", "false");
        clock.addAndGet(181_000);
        assertThat(service.discover("1").getFirst()).doesNotContainKey("running");
        verify(client, times(2)).mapInterfaceItems(anySet());
        verify(client, times(2)).mapItemValues(anySet());
    }
}
