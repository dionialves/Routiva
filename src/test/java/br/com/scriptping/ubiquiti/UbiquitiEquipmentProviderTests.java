package br.com.scriptping.ubiquiti;

import br.com.scriptping.hosts.HostConnections;
import br.com.scriptping.equipment.EquipmentProvider.Connection;
import br.com.scriptping.mikrotik.WirelessQuality;
import br.com.scriptping.zabbix.Host;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UbiquitiEquipmentProviderTests {
    private final Host host = new Host("42", "Radio", "192.0.2.42", List.of());
    private final Connection connection = new Connection("reader", "secret", 2222);
    private final UbiquitiSsh ssh = mock(UbiquitiSsh.class);
    private final UbiquitiSsh.Link link = mock(UbiquitiSsh.Link.class);
    private final HostConnections settings = mock(HostConnections.class);
    private final WirelessQuality quality = mock(WirelessQuality.class);
    private final UbiquitiEquipmentProvider provider = new UbiquitiEquipmentProvider(settings, ssh, new ObjectMapper(), quality);
    @BeforeEach void setup() throws Exception {
        when(settings.require(host)).thenReturn(connection);
        when(ssh.connect(host.ip(), connection)).thenReturn(link);
        when(quality.update(anyString(), anyList(), anyLong())).thenAnswer(call -> call.getArgument(1));
    }
    @Test void connectionTestUsesSuppliedCredentialsWithoutSavedConfiguration() throws Exception {
        when(link.command("mca-status")).thenReturn("platform=Rocket M5,firmwareVersion=XM.v6.3.11\nuptime=500");
        provider.testConnection(host, connection);
        verifyNoInteractions(settings);
        verify(link).close();
    }
    @Test void emptyStationListIsSuccessfulAndTimestampComesFromServer() throws Exception {
        when(link.command("wstalist")).thenReturn("[]");
        long before = System.currentTimeMillis();
        var snapshot = provider.read(host, "wireless", null);
        assertTrue(snapshot.rows().isEmpty());
        assertEquals("", snapshot.warning());
        assertTrue(snapshot.measuredAt() >= before);
        verify(quality).update(eq("Ubiquiti:42"), eq(List.of()), anyLong());
        verify(link).close();
    }
    @Test void malformedStationListFailsInsteadOfReportingZeroClients() throws Exception {
        when(link.command("wstalist")).thenReturn("command not found");
        assertThrows(ResponseStatusException.class, () -> provider.read(host, "wireless", null));
        verifyNoInteractions(quality);
        verify(link).close();
    }
    @Test void interfaceNamesAreNeverInterpolatedIntoSshCommands() {
        assertThrows(ResponseStatusException.class, () -> provider.read(host, "traffic", "eth0; reboot"));
        verifyNoInteractions(ssh);
    }
    @Test void missingInterfaceIsRejected() throws Exception {
        when(link.command("cat /proc/net/dev")).thenReturn("eth0: 100 1 0 0 0 0 0 0 200 1 0 0 0 0 0 0");
        when(link.command("ifconfig -a")).thenReturn("eth0 Link encap:Ethernet\n UP RUNNING MTU:1500");
        assertThrows(ResponseStatusException.class, () -> provider.read(host, "traffic", "eth9"));
    }
}
