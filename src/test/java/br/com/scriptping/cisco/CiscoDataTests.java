package br.com.scriptping.cisco;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CiscoDataTests {
    private String fixture(int sample) throws Exception {
        try (var input = getClass().getResourceAsStream("/cisco/interfaces-" + sample + ".txt")) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    @Test void parsesBothRealSamplesWithSfpPlusRepresentationAndWithoutInventingSubinterfaceCounters() throws Exception {
        var first = CiscoData.interfaces(fixture(1));
        var second = CiscoData.interfaces(fixture(2));
        assertThat(first).hasSize(21);
        assertThat(second).hasSize(first.size());
        assertThat(first.stream().filter(r -> r.get("name").startsWith("TenGigabitEthernet")))
            .hasSize(3).allSatisfy(row -> assertThat(row).containsEntry("connector", "sfpplus").containsEntry("supported", "10 Gbps"));
        assertThat(first.getFirst()).containsEntry("disabled", "true").containsEntry("running", "false");
        var a = first.stream().filter(r -> "TenGigabitEthernet0/2/0".equals(r.get("name"))).findFirst().orElseThrow();
        var b = second.stream().filter(r -> a.get("name").equals(r.get("name"))).findFirst().orElseThrow();
        assertThat(a).containsEntry("rx-byte", "304202513024971").containsEntry("running", "true")
            .containsEntry("supported", "10 Gbps").containsEntry("rate", "10000Mbps").containsEntry("connector", "sfpplus");
        assertThat(new java.math.BigInteger(b.get("rx-byte"))).isGreaterThan(new java.math.BigInteger(a.get("rx-byte")));
        assertThat(first.stream().filter(r -> r.get("name").equals("Port-channel2.106")).findFirst().orElseThrow())
            .doesNotContainKeys("rx-byte", "tx-byte");
    }
    @Test void preservesEmptyDescriptionsAndDistinguishesAdminAndProtocolState() {
        var rows = CiscoData.status("Interface Status Protocol Description\nGi0/0/0 admin down down\nTe0/2/0 up up\nTe0/3/0 up down Test\n");
        assertThat(rows).hasSize(3);
        assertThat(rows.get(0)).containsEntry("name", "GigabitEthernet0/0/0").containsEntry("disabled", "true").doesNotContainKey("comment");
        assertThat(rows.get(1)).containsEntry("running", "true").doesNotContainKeys("rx-byte", "tx-byte", "comment");
        assertThat(rows.get(2)).containsEntry("running", "false").containsEntry("disabled", "false").containsEntry("comment", "Test");
    }
    @Test void acceptsOnlyOperationalCommandsAndStrictInterfaceNames() {
        assertThat(CiscoCli.allowed("show interfaces TenGigabitEthernet0/2/0")).isTrue();
        assertThat(CiscoCli.allowed("configure terminal")).isFalse();
        assertThatThrownBy(() -> CiscoData.cliName("Gi0/0/0;reload")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CiscoData.interfaces("% Invalid input detected")).isInstanceOf(IllegalArgumentException.class);
        assertThat(CiscoData.overview("Cisco IOS XE Software, Version 16.09.08\ncisco ASR1002-X (2RU-X) processor\nASR uptime is 2 weeks\n"))
            .containsEntry("board-name", "ASR1002-X").containsEntry("version", "16.09.08");
    }
}
