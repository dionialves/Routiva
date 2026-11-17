package br.com.scriptping.ubiquiti;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UbiquitiDataTests {
    @Test void normalizesOverviewWithoutTreatingDisconnectedZeroSignalAsMeasurement() {
        var status = UbiquitiData.status("deviceName=LiteBeam 5AC,deviceId=AA,firmwareVersion=WA.v8.7.11,platform=LiteBeam 5AC Gen2\r\n\r\nuptime=123\r\nmemTotal=61960\r\nmemFree=18164\r\nfreq=5280\r\nchanbw=20\r\nsignal=0\r\nwlanConnections=0\r\n");
        var row = UbiquitiData.overview(status);
        assertEquals("LiteBeam 5AC Gen2", row.get("board-name"));
        assertEquals("WA.v8.7.11", row.get("version"));
        assertEquals("123s", row.get("uptime"));
        assertEquals("63447040", row.get("total-memory"));
        assertEquals("5280 MHz / 20 MHz", row.get("channel"));
        assertFalse(row.containsKey("signal"));
    }
    @Test void mapsRocketStationsAndKeepsLargeCountersAndRateUnits() throws Exception {
        var row = UbiquitiData.wireless("""
            [{"mac":"18:0d:2c:15:8c:b0","associd":5,"tx":78.0,"rx":104.0,"signal":-61,
            "ccq":94,"uptime":78614,"distance":1500,"noisefloor":-90,
            "stats":{"rx_bytes":275556363,"tx_bytes":33844825559},
            "airmax":{"quality":0,"capacity":0}}]
            """, new ObjectMapper()).getFirst();
        assertEquals("18:0D:2C:15:8C:B0", row.get("mac-address"));
        assertEquals("29.0", row.get("signal-to-noise"));
        assertEquals("94", row.get("tx-ccq"));
        assertFalse(row.containsKey("rx-ccq"));
        assertEquals("78.0Mbps", row.get("tx-rate"));
        assertEquals("33844825559", row.get("tx-bytes"));
        assertEquals("1.5", row.get("distance"));
        assertFalse(row.containsKey("p-throughput"));
    }
    @Test void emptyAcListIsValidButMalformedResponseMustFail() throws Exception {
        assertTrue(UbiquitiData.wireless("[]", new ObjectMapper()).isEmpty());
        assertThrows(Exception.class, () -> UbiquitiData.wireless("{}", new ObjectMapper()));
        var row = UbiquitiData.wireless("[{\"mac\":\"AA:BB:CC:DD:EE:FF\",\"signal\":0,\"noisefloor\":-96}]", new ObjectMapper()).getFirst();
        assertFalse(row.containsKey("signal-to-noise"));
        assertFalse(row.containsKey("tx-ccq"));
    }
    @Test void parsesVlanInterfacesAndDisabledPorts() {
        var rows = UbiquitiData.interfaces("""
             eth0.644: 916422872 15133050 2 3 0 0 0 0 64612795 953332 4 5 0 0 0 0
             eth1: 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0
            """, """
            eth0.644 Link encap:Ethernet HWaddr 74:83:C2:A1:1E:3E
                      UP BROADCAST RUNNING MULTICAST MTU:1500 Metric:1
            eth1      Link encap:Ethernet HWaddr 26:A4:3C:F1:8C:C6
                      BROADCAST MULTICAST MTU:1500 Metric:1
            """);
        assertEquals("vlan", rows.getFirst().get("type"));
        assertEquals("916422872", rows.getFirst().get("rx-byte"));
        assertEquals("4", rows.getFirst().get("tx-error"));
        assertEquals("true", rows.getFirst().get("running"));
        assertEquals("1500", rows.getFirst().get("mtu"));
        assertEquals("true", rows.get(1).get("disabled"));
    }
}
