package br.com.scriptping.mimosa;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MimosaEquipmentProviderTests {
    private final ObjectMapper json = new ObjectMapper();
    private com.fasterxml.jackson.databind.JsonNode dashboard() throws Exception {
        return json.readTree("""
            {"realtime":{"device_info":{"Version":"2.10.0","Model":"B02","DeviceName":"Radio",
            "MacEther":"00:11:22:33:44:55","ethernetSpeed":"Link Down"},
            "wan_stats":{"Tx#02":3741736756,"Rx#05":1550499589},"throughput":{"throughput":"123:10,20;"}},
            "config":{"WirelessConfig":{"PassPhrase":"secret"},"Services":{"RESTPassword":"secret"}}}
            """);
    }
    @Test void exposesOnlyAllowlistedOverviewFields() throws Exception {
        var result = MimosaEquipmentProvider.snapshot(dashboard(), "overview", null, 123);
        assertEquals("2.10.0", result.rows().getFirst().get("version"));
        assertEquals("Mimosa", result.rows().getFirst().get("manufacturer"));
        assertFalse(json.writeValueAsString(result).contains("secret"));
        assertEquals(123, result.measuredAt());
    }
    @Test void mapsConfirmedEthernetOctetsWithoutUsingRadioThroughput() throws Exception {
        var result = MimosaEquipmentProvider.snapshot(dashboard(), "connection", "ethernet", 123);
        var row = result.rows().getFirst();
        assertEquals("false", row.get("running"));
        assertEquals("1550499589", row.get("rx-byte"));
        assertEquals("3741736756", row.get("tx-byte"));
        assertFalse(row.containsKey("tx-bits-per-second"));
        assertTrue(result.warning().isBlank());
    }
    @Test void discoveryPreservesInterfaceNameAndSeparatesPhysicalModesFromLinkSpeed() throws Exception {
        var data = dashboard();
        ((com.fasterxml.jackson.databind.node.ObjectNode) data.path("realtime").path("device_info")).put("ethernetSpeed", "100 Mbps Full Duplex");
        ((com.fasterxml.jackson.databind.node.ObjectNode) data).putObject("config").putObject("Ethernet").put("AdvertiseOptions", "1000BaseT-Full,100BaseT-Full");
        var row = MimosaEquipmentProvider.snapshot(data, "discover", null, 1).rows().getFirst();
        assertEquals("Ethernet (RJ45)", row.get("name"));
        assertEquals("electrical", row.get("connector"));
        assertEquals("1000Mbps,100Mbps", row.get("advertising"));
        assertEquals("100Mbps", row.get("rate"));
        assertEquals("true", row.get("running"));
        assertEquals(row, MimosaEquipmentProvider.snapshot(data, "connection", row.get("name"), 1).rows().getFirst());
    }
    @Test void discoversWirelessAndUsesOnlyItsOwnFreshTrafficRates() throws Exception {
        var data = dashboard();
        ((com.fasterxml.jackson.databind.node.ObjectNode) data.path("realtime")).putObject("connection").put("connected", "1").put("SSID", "Test link");
        ((com.fasterxml.jackson.databind.node.ObjectNode) data).putObject("wirelessTraffic").put("tput1min", "100:12.5,25.5;");
        var result = MimosaEquipmentProvider.snapshot(data, "discover", null, 101000);
        assertEquals(2, result.rows().size());
        var wireless = MimosaEquipmentProvider.snapshot(data, "connection", "Wireless", 101000).rows().getFirst();
        assertEquals("wireless", wireless.get("connector"));
        assertEquals("true", wireless.get("running"));
        assertEquals("100", wireless.get("tx-bits-per-second"));
        assertEquals("204", wireless.get("rx-bits-per-second"));
        assertFalse(wireless.containsKey("rx-byte"));
        assertFalse(MimosaEquipmentProvider.snapshot(data, "connection", "Wireless", 150000).rows().getFirst().containsKey("rx-bits-per-second"));
        var row = new java.util.HashMap<String, String>();
        MimosaEquipmentProvider.wirelessRates(row, "100:nan,5;", 101000);
        assertTrue(row.isEmpty());
        MimosaEquipmentProvider.wirelessRates(row, "100:0,0;", 101000);
        assertEquals("0", row.get("rx-bits-per-second"));
    }
    @Test void missingCountersStayUnavailable() throws Exception {
        var data = dashboard();
        ((com.fasterxml.jackson.databind.node.ObjectNode) data.path("realtime")).remove("wan_stats");
        var result = MimosaEquipmentProvider.snapshot(data, "interfaces", null, 1);
        assertFalse(result.rows().getFirst().containsKey("rx-byte"));
        assertFalse(result.warning().isBlank());
    }
    @Test void rejectsLoginHtmlAndIncompleteDashboard() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> MimosaEquipmentProvider.snapshot(json.readTree("{}"), "overview", null, 0));
        assertThrows(IllegalArgumentException.class, () -> MimosaEquipmentProvider.snapshot(json.readTree("{\"realtime\":{}}"), "overview", null, 0));
    }
    @Test void unknownEthernetStatusStaysUnknown() throws Exception {
        var data = dashboard();
        ((com.fasterxml.jackson.databind.node.ObjectNode) data.path("realtime").path("device_info")).put("ethernetSpeed", "");
        assertFalse(MimosaEquipmentProvider.snapshot(data, "interfaces", null, 1).rows().getFirst().containsKey("running"));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
            () -> MimosaEquipmentProvider.snapshot(data, "connection", "not-a-port", 1));
    }
}
