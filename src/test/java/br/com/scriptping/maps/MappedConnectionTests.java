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
    @MockitoBean MikrotikService mikrotik;
    @MockitoBean br.com.scriptping.hosts.HostConnections connections;
    @MockitoBean br.com.scriptping.hosts.HostRepository hosts;

    void equipment() {
        when(hosts.target(anyString())).thenAnswer(call -> new Host(call.getArgument(0), "Equipment", "10.0.0.1", List.of()));
        when(hosts.credentials(anyString())).thenReturn(new br.com.scriptping.hosts.HostRepository.Credentials("MikroTik", "map-reader", "encrypted", 8729, true, "ACTIVE", "", "10.0.0.1", true, 1L));
        when(connections.require(any())).thenAnswer(call -> new EquipmentProvider.Connection("map-reader", ((Host)call.getArgument(0)).id().equals("a") ? "test-a" : "test-b", 8729, true));
        when(inventory.hosts()).thenReturn(List.of(new Host("a", "A", "10.0.0.1", List.of()), new Host("b", "B", "10.0.0.2", List.of())));
        when(mikrotik.query(any(), eq("interfaces"), isNull(), any(EquipmentProvider.Connection.class))).thenReturn(new MikrotikService.Snapshot(1, List.of(Map.of(".id", "*1", "name", "ether1", "type", "ether"), Map.of("name", "bridge", "type", "bridge")), ""));
    }
    @Test void discoversWithLocalHostAuthenticationWithoutRequiringMonitoredInventory() {
        equipment();
        when(inventory.hosts()).thenReturn(List.of());
        var rows = List.of(Map.of("name", "uplink", "comment", "Core", "rate", "10Gbps", "running", "true"));
        when(mikrotik.query(any(), eq("discover"), isNull(), any(EquipmentProvider.Connection.class)))
            .thenReturn(new MikrotikService.Snapshot(1, rows, ""));
        assertThat(controller.discover("a")).isEqualTo(rows);
        verify(mikrotik).query(eq(hosts.target("a")), eq("discover"), isNull(),
            eq(new EquipmentProvider.Connection("map-reader", "test-a", 8729, true)));
        when(hosts.credentials("a")).thenReturn(null);
        assertThatThrownBy(() -> controller.discover("a")).isInstanceOf(ResponseStatusException.class);
        verify(mikrotik, times(1)).query(any(), eq("discover"), isNull(), any(EquipmentProvider.Connection.class));
    }
    @Test void savesIndependentEntityAndRejectsVirtualOrMissingInterfaces() {
        equipment();
        var saved = controller.save(new MappedConnectionController.Connection(null, "Cabo A-B", "a", "ether1", "b", "ether1"));
        assertThat(saved.id()).isNotBlank();
        assertThat(controller.all()).containsExactly(saved);
        var credentials = org.mockito.ArgumentCaptor.forClass(EquipmentProvider.Connection.class);
        verify(mikrotik, times(2)).query(any(), eq("interfaces"), isNull(), credentials.capture());
        assertThat(credentials.getValue().login()).isEqualTo("map-reader");
        assertThat(credentials.getAllValues()).extracting(EquipmentProvider.Connection::password).containsExactly("test-a", "test-b");
        assertThat(credentials.getValue().port()).isEqualTo(8729);
        assertThat(credentials.getValue().tls()).isTrue();
        assertThatThrownBy(() -> controller.save(new MappedConnectionController.Connection(null, "Inválida", "a", "bridge", "b", "ether1"))).isInstanceOf(ResponseStatusException.class);
        assertThat(controller.all()).hasSize(1);
    }
    @Test void refusesMissingHostCredentialsWithoutQueryingEquipment() {
        when(inventory.hosts()).thenReturn(List.of(new Host("a", "A", "10.0.0.1", List.of())));
        assertThatThrownBy(() -> controller.interfaces("a")).isInstanceOfSatisfying(ResponseStatusException.class,
            ex -> assertThat(ex.getReason()).contains("autenticação"));
        verifyNoInteractions(mikrotik);
    }

    @Test void cachesReadingsAndSeparatesCollectionErrorFromDown() {
        equipment();
        var c = controller.save(new MappedConnectionController.Connection(null, "Rede", "a", "ether1", "b", "ether1"));
        when(mikrotik.query(any(), eq("connection"), eq("ether1"), any(EquipmentProvider.Connection.class))).thenAnswer(call -> {
            Host host = call.getArgument(0);
            if (host.id().equals("b")) throw new IllegalStateException("API indisponível");
            return new MikrotikService.Snapshot(System.currentTimeMillis(), List.of(Map.of("running", "true", "rx-bits-per-second", "1000", "tx-bits-per-second", "2000")), "");
        });
        var first = controller.readings(Set.of(c.id())).getFirst();
        assertThat(first.a().up()).isTrue();
        assertThat(first.a().rxBps()).isEqualTo("1000");
        assertThat(first.b().error()).isNotNull();
        assertThat(controller.readings(Set.of(c.id()))).containsExactly(first);
        verify(mikrotik, times(2)).query(any(), eq("connection"), eq("ether1"), any(EquipmentProvider.Connection.class));
        assertThat(controller.readings(Set.of("missing"))).isEmpty();
    }
    @Test void firstCounterSampleExpiresEarlyButMeasuredRatesKeepNormalCache() {
        equipment();
        var controller = new MappedConnectionController(jdbc, inventory, mikrotik, connections, hosts);
        var c = controller.save(new MappedConnectionController.Connection(null, "Rede", "a", "ether1", "b", "ether1"));
        long now = System.currentTimeMillis();
        var firstRows = List.of(Map.of("running", "true", "rx-byte", "1000", "tx-byte", "2000"));
        var nextRows = List.of(Map.of("running", "true", "rx-byte", "2000", "tx-byte", "4000"));
        when(mikrotik.query(any(), eq("connection"), eq("ether1"), any(EquipmentProvider.Connection.class)))
            .thenReturn(new MikrotikService.Snapshot(now - 1500, firstRows, ""))
            .thenReturn(new MikrotikService.Snapshot(now - 1500, firstRows, ""))
            .thenReturn(new MikrotikService.Snapshot(now, nextRows, ""));
        var first = controller.readings(Set.of(c.id())).getFirst();
        assertThat(first.a().txBps()).isNull();
        var second = controller.readings(Set.of(c.id())).getFirst();
        assertThat(second.a().txBps()).isEqualTo("10666");
        assertThat(second.a().rxBps()).isEqualTo("5333");
        assertThat(controller.readings(Set.of(c.id()))).containsExactly(second);
        verify(mikrotik, times(4)).query(any(), eq("connection"), eq("ether1"), any(EquipmentProvider.Connection.class));
    }
    @Test void computesRatesWithUnsigned64BitCountersAndRejectsResetsAndGaps() {
        var first = MappedConnectionController.counter("18446744073709550000");
        var second = MappedConnectionController.counter("18446744073709551000");
        assertThat(MappedConnectionController.rate(second, first, 2000)).isEqualTo("4000");
        assertThat(MappedConnectionController.rate(first, second, 2000)).isNull();
        assertThat(MappedConnectionController.rate(second, first, 0)).isNull();
        assertThat(MappedConnectionController.rate(second, first, 31000)).isNull();
        assertThat(MappedConnectionController.counter("18446744073709551616")).isNull();
        assertThat(MappedConnectionController.counter("-1")).isNull();
        assertThat(MappedConnectionController.capacity("2.5Gbps")).isEqualTo(2500000000L);
        assertThat(MappedConnectionController.capacity("unknown")).isNull();
    }
}
