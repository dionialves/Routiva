package br.com.scriptping.datacom;

import br.com.scriptping.equipment.EquipmentProvider;
import br.com.scriptping.hosts.HostConnections;
import br.com.scriptping.ubiquiti.UbiquitiSsh;
import br.com.scriptping.zabbix.Host;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DatacomProviderTests {
    @Test void usesSavedHostSshAndExcludesWirelessAndOnuCommands() throws Exception {
        var settings = mock(HostConnections.class); var ssh = mock(UbiquitiSsh.class);
        var link = mock(UbiquitiSsh.Link.class); var cli = mock(DatacomCli.class);
        var host = new Host("42", "OLT", "192.0.2.42", List.of());
        var credentials = new EquipmentProvider.Connection("reader", "secret", 2222);
        when(settings.require(host)).thenReturn(credentials);
        when(ssh.connect(host.ip(), credentials)).thenReturn(link); when(link.datacomCli()).thenReturn(cli);
        when(cli.command("show interface link")).thenReturn("Interface  Admin  Link\nten-gigabit-ethernet-1/1/1  Up  Up\ngpon-1/1/1  Up  Up");
        when(cli.command("show interface ten-gigabit-ethernet 1/1/1")).thenReturn("MTU : 1500\nSpeed : 10G");
        when(cli.command("show interface ten-gigabit-ethernet 1/1/1 statistics")).thenReturn("In Octets : 100\nOut Octets : 200");
        var provider = new DatacomEquipmentProvider(settings, ssh);
        var reading = provider.read(host, "connection", "ten-gigabit-ethernet-1/1/1");
        assertThat(reading.rows().getFirst()).containsEntry("running", "true").containsEntry("rx-byte", "100");
        verify(cli).close(); verify(link).close();
        assertThat(provider.capabilities()).doesNotContain("wireless", "onu");
        assertThatThrownBy(() -> provider.read(host, "wireless", null)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> provider.read(host, "connection", "x; reboot")).isInstanceOf(ResponseStatusException.class);
        verify(ssh, times(1)).connect(anyString(), any());
        verify(cli, never()).command(contains("onu"));
        when(cli.command("show interface link")).thenReturn("ten-gigabit-ethernet-1/1/1");
        assertThatThrownBy(() -> provider.read(host, "connection", "ten-gigabit-ethernet-1/1/1"))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("Não foi possível");
    }
    @Test void discoveryUsesOnlyTheInterfaceListEvenWhenPerPortCommandsAreUnavailable() throws Exception {
        var settings = mock(HostConnections.class); var ssh = mock(UbiquitiSsh.class);
        var link = mock(UbiquitiSsh.Link.class); var cli = mock(DatacomCli.class);
        var host = new Host("42", "Switch", "192.0.2.42", List.of());
        var credentials = new EquipmentProvider.Connection("reader", "secret", 22);
        when(ssh.connect(host.ip(), credentials)).thenReturn(link); when(link.datacomCli()).thenReturn(cli);
        var output = new StringBuilder("Interface  Admin  Link\n");
        for (int port = 1; port <= 48; port++) output.append("gigabit-ethernet-1/1/").append(port).append("  Up  Down\n");
        output.append("gpon-1/1/1  Up  Up\n");
        when(cli.command("show interface link")).thenReturn(output.toString());
        var provider = new DatacomEquipmentProvider(settings, ssh);
        var snapshot = provider.query(host, "discover", null, credentials);
        assertThat(snapshot.rows()).hasSize(48).allSatisfy(row -> {
            assertThat(row).containsEntry("type", "ether").containsEntry("running", "false");
            assertThat(row).doesNotContainKeys("rx-byte", "connector", "supported");
        });
        verify(cli).command("show interface link");
        verify(cli).command("show platform");
        verify(cli).close(); verifyNoMoreInteractions(cli); verify(link).close();
    }
    @Test void discoveryDistinguishesUnsupportedOutputFromCommandRejection() throws Exception {
        var ssh = mock(UbiquitiSsh.class); var link = mock(UbiquitiSsh.Link.class); var cli = mock(DatacomCli.class);
        var host = new Host("42", "Switch", "192.0.2.42", List.of());
        var credentials = new EquipmentProvider.Connection("reader", "secret", 22);
        when(ssh.connect(host.ip(), credentials)).thenReturn(link); when(link.datacomCli()).thenReturn(cli);
        var provider = new DatacomEquipmentProvider(mock(HostConnections.class), ssh);
        when(cli.command("show interface link")).thenReturn("Unexpected table layout");
        assertThatThrownBy(() -> provider.query(host, "discover", null, credentials))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("formato de show interface link");
        when(cli.command("show interface link")).thenThrow(new DatacomCli.CommandRejectedException());
        assertThatThrownBy(() -> provider.query(host, "discover", null, credentials))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("recusou o comando");
    }

}
