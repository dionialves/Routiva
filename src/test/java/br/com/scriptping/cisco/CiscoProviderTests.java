package br.com.scriptping.cisco;

import br.com.scriptping.equipment.EquipmentProvider;
import br.com.scriptping.hosts.HostConnections;
import br.com.scriptping.ubiquiti.UbiquitiSsh;
import br.com.scriptping.zabbix.Host;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CiscoProviderTests {
    @Test void sharesOneSessionAcrossInterfacesAndRolesAndDestinationNeverRequestsCounters() throws Exception {
        var ssh = mock(UbiquitiSsh.class);
        var link = mock(UbiquitiSsh.Link.class);
        var cli = mock(CiscoCli.class);
        var host = new Host("42", "Router", "192.0.2.42", List.of());
        var credentials = new EquipmentProvider.Connection("reader", "secret", 22);
        when(ssh.connectCisco(host.ip(), credentials)).thenReturn(link);
        when(link.ciscoCli()).thenReturn(cli);
        when(cli.command("show interfaces description")).thenReturn("Te0/2/0 up up\nTe0/3/0 up down\n");
        when(cli.command("show interfaces TenGigabitEthernet0/2/0")).thenReturn("TenGigabitEthernet0/2/0 is up, line protocol is up\n 10 packets input, 100 bytes\n 20 packets output, 200 bytes\n");
        var provider = new CiscoEquipmentProvider(mock(HostConnections.class), ssh);
        try {
            var destination = provider.query(host, "connection-status", "TenGigabitEthernet0/3/0", credentials);
            assertThat(destination.rows().getFirst()).containsEntry("running", "false").doesNotContainKeys("rx-byte", "tx-byte");
            verify(cli).command("show interfaces description");
            verifyNoMoreInteractions(cli);
            var origin = provider.query(host, "connection", "TenGigabitEthernet0/2/0", credentials);
            assertThat(origin.rows().getFirst()).containsEntry("rx-byte", "100").containsEntry("tx-byte", "200");
            verify(ssh).connectCisco(host.ip(), credentials);
            verify(link).ciscoCli();
            verify(link, never()).close();
        } finally { provider.closeMappedSessions(); }
        verify(cli).close(); verify(link).close();
    }
}
