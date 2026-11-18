package br.com.scriptping.datacom;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DatacomDataTests {
    @Test void normalizesNativeNamesAndReadsLabelledStatesWithoutGuessingMissingValues() {
        var rows = DatacomData.links("""
            Interface                          Admin  Link  Speed  Description
            ten-gigabit-ethernet-1/1/1          Up     Up    10G    Core
            gigabit-ethernet-1/1/2              Up     Down  -      Spare
            gpon-1/1/1                         Up     Up    -      PON
            """);
        assertThat(rows).hasSize(3);
        assertThat(rows.getFirst()).containsEntry("name", "ten-gigabit-ethernet-1/1/1")
            .containsEntry("running", "true").containsEntry("disabled", "false").containsEntry("rate", "10Gbps");
        assertThat(rows.get(1)).containsEntry("running", "false").doesNotContainKey("rate");
        assertThat(rows.get(2)).containsEntry("type", "gpon");
        assertThat(DatacomData.cliName(rows.getFirst().get("name"))).isEqualTo("ten-gigabit-ethernet 1/1/1");
        assertThat(DatacomData.links("gigabit-ethernet 1/1/1").getFirst()).doesNotContainKeys("running", "disabled", "rx-byte");
    }
    @Test void readsCountersAsUnsigned64BitAndRejectsNegativeOrMissingCounters() {
        var row = new HashMap<String, String>();
        DatacomData.details(row, """
            port-admin up
            link-status up
            mtu 1500
            In Octets : 18446744073709551615
            Out Octets : 120000
            In Errors : 3
            Out Errors : -1
            """);
        assertThat(row).containsEntry("running", "true").containsEntry("disabled", "false")
            .containsEntry("rx-byte", "18446744073709551615").containsEntry("mtu", "1500")
            .containsEntry("rx-error", "3").doesNotContainKey("tx-error");
        assertThat(DatacomData.rate("18446744073709550000", "18446744073709551000", 1000)).isEqualTo("8000");
        assertThat(DatacomData.rate("100", "10", 1000)).isNull();
        assertThat(DatacomData.rate(null, "10", 1000)).isNull();
        assertThat(DatacomData.rate("0", "10", 31000)).isNull();
    }
    @Test void validatesPlatformAndKeepsUnavailableOverviewFieldsAbsent() {
        var row = DatacomData.overview("1 DM4612 - -\n1/1 Card Master Ready 10.8.0", "", "active 12.5% 10.0%", "total 512 MB\nfree 128 MB", "up 5 days, 12:34, load average: 0.1");
        assertThat(row).containsEntry("board-name", "DM4612").containsEntry("version", "10.8.0")
            .containsEntry("cpu-load", "12.5").containsEntry("free-memory", "134217728").containsEntry("uptime", "5 days, 12:34");
        assertThat(DatacomData.overview("DM4170", "", "", "", "")).doesNotContainKeys("version", "cpu-load", "free-memory", "uptime");
        assertThatThrownBy(() -> DatacomData.overview("Access denied", "", "", "", "")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void permitsOnlyReadOnlyCommandsAndNeverOnuQueriesOrInjectedCommands() {
        assertThat(DatacomCli.allowed("show interface gpon 1/1/1 statistics")).isTrue();
        for (String command : List.of("config", "show running-config", "show interface gpon 1/1/1 onu 1", "show platform; reboot", "show interface gigabit-ethernet 1/1/1\nshutdown"))
            assertThat(DatacomCli.allowed(command)).as(command).isFalse();
        assertThatThrownBy(() -> DatacomData.cliName("ge1; reboot")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DatacomData.links("syntax error")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void readsTheReportedSwitchOutputWithGroupedFamiliesAndMultilineHeaders() throws Exception {
        String output;
        try (var input = getClass().getResourceAsStream("/datacom/switch-interface-link.txt")) {
            output = new String(java.util.Objects.requireNonNull(input).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        var rows = DatacomData.links(output);
        assertThat(rows).hasSize(26);
        assertThat(rows).extracting(row -> row.get("name")).doesNotHaveDuplicates();
        assertThat(rows.getFirst()).containsEntry("name", "ten-gigabit-ethernet-1/1/1")
            .containsEntry("running", "true").containsEntry("disabled", "false").containsEntry("rate", "10Gbps").containsEntry("comment", "ALT");
        assertThat(rows.get(1)).containsEntry("running", "false").containsEntry("disabled", "true").doesNotContainKeys("rate", "comment");
        assertThat(rows.get(18)).containsEntry("running", "false").containsEntry("disabled", "false");
        assertThat(rows.get(24)).containsEntry("name", "forty-gigabit-ethernet-1/1/1")
            .containsEntry("running", "true").containsEntry("disabled", "false").containsEntry("rate", "40Gbps").containsEntry("comment", "SWCore-000");
        assertThat(DatacomData.cliName(rows.get(24).get("name"))).isEqualTo("forty-gigabit-ethernet 1/1/1");
    }

    @Test void dm4250HardwareIdentifiesUnpluggedCagesWithoutUsingLinkSpeed() throws Exception {
        String platform, interfaces;
        try (var input = getClass().getResourceAsStream("/datacom/dm4250-platform.txt")) {
            platform = new String(java.util.Objects.requireNonNull(input).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        try (var input = getClass().getResourceAsStream("/datacom/switch-interface-link.txt")) {
            interfaces = new String(java.util.Objects.requireNonNull(input).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        var rows = DatacomData.links(interfaces);
        DatacomData.applyHardware(rows, platform);
        assertThat(rows.subList(0, 24)).allSatisfy(row -> assertThat(row)
            .containsEntry("connector", "sfpplus").containsEntry("supported", "10 Gbps"));
        assertThat(rows.subList(24, 26)).allSatisfy(row -> assertThat(row)
            .containsEntry("connector", "qsfpplus").containsEntry("supported", "40 Gbps"));
        assertThat(rows.get(1)).containsEntry("disabled", "true").doesNotContainKey("rate");
        var slower = new HashMap<>(rows.getFirst()); slower.put("rate", "1Gbps");
        DatacomData.applyHardware(List.of(slower), platform);
        assertThat(slower).containsEntry("rate", "1Gbps").containsEntry("supported", "10 Gbps");
        for (String unknown : List.of(platform.replace("DM4250", "DM4270"), platform.replace("24XS+2QX", "OTHER"))) {
            var fresh = DatacomData.links(interfaces);
            DatacomData.applyHardware(fresh, unknown);
            assertThat(fresh).allSatisfy(row -> assertThat(row).doesNotContainKeys("connector", "supported"));
        }
    }

}
