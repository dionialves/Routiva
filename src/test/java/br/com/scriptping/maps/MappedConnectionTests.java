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
    @MockitoBean br.com.scriptping.datacom.DatacomEquipmentProvider datacom;
    @MockitoBean br.com.scriptping.mimosa.MimosaEquipmentProvider mimosa;
    @MockitoBean br.com.scriptping.cisco.CiscoEquipmentProvider cisco;
    @MockitoBean br.com.scriptping.hosts.HostConnections connections;
    @MockitoBean br.com.scriptping.hosts.HostRepository hosts;

    @org.junit.jupiter.api.BeforeEach
    void freshReadingCache() {
        controller = new MappedConnectionController(jdbc, inventory, mikrotik, connections, hosts, datacom, mimosa, cisco);
    }

    void equipment() {
        when(hosts.target(anyString())).thenAnswer(call -> new Host(call.getArgument(0), "Equipment", "10.0.0.1", List.of()));
        when(hosts.credentials(anyString())).thenReturn(new br.com.scriptping.hosts.HostRepository.Credentials("MikroTik", "map-reader", "encrypted", 8729, true, "ACTIVE", "", "10.0.0.1", true, 1L));
        when(connections.require(any())).thenAnswer(call -> new EquipmentProvider.Connection("map-reader", ((Host)call.getArgument(0)).id().equals("a") ? "test-a" : "test-b", 8729, true));
        when(inventory.hosts()).thenReturn(List.of(new Host("a", "A", "10.0.0.1", List.of()), new Host("b", "B", "10.0.0.2", List.of())));
        when(mikrotik.query(any(), eq("connection-status"), anyString(), any(EquipmentProvider.Connection.class)))
            .thenAnswer(call -> new MikrotikService.Snapshot(System.currentTimeMillis(), List.of(Map.of("running", "true")), ""));
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
    @Test void validatesMapDraftWithoutPersistingAndRejectsVirtualInterfaces() {
        equipment();
        var saved = controller.validate(new MappedConnectionController.Connection(null, "Cabo A-B", "a", "ether1", "b", "ether1"));
        assertThat(saved.id()).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM network_map", Integer.class)).isZero();
        var credentials = org.mockito.ArgumentCaptor.forClass(EquipmentProvider.Connection.class);
        verify(mikrotik, times(2)).query(any(), eq("interfaces"), isNull(), credentials.capture());
        assertThat(credentials.getValue().login()).isEqualTo("map-reader");
        assertThat(credentials.getAllValues()).extracting(EquipmentProvider.Connection::password).containsExactly("test-a", "test-b");
        assertThat(credentials.getValue().port()).isEqualTo(8729);
        assertThat(credentials.getValue().tls()).isTrue();
        assertThatThrownBy(() -> controller.validate(new MappedConnectionController.Connection(null, "Inválida", "a", "bridge", "b", "ether1"))).isInstanceOf(ResponseStatusException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM network_map", Integer.class)).isZero();
    }
    @Test void refusesMissingHostCredentialsWithoutQueryingEquipment() {
        when(inventory.hosts()).thenReturn(List.of(new Host("a", "A", "10.0.0.1", List.of())));
        assertThatThrownBy(() -> controller.interfaces("a")).isInstanceOfSatisfying(ResponseStatusException.class,
            ex -> assertThat(ex.getReason()).contains("autenticação"));
        verifyNoInteractions(mikrotik);
    }

    @Test void cachesReadingsAndSeparatesCollectionErrorFromDown() {
        equipment();
        var c = controller.validate(new MappedConnectionController.Connection(null, "Rede", "a", "ether1", "b", "ether1"));
        persist(c);
        when(mikrotik.query(any(), eq("connection"), eq("ether1"), any(EquipmentProvider.Connection.class)))
            .thenAnswer(call -> new MikrotikService.Snapshot(System.currentTimeMillis(), List.of(Map.of("running", "true", "rx-bits-per-second", "1000", "tx-bits-per-second", "2000")), ""));
        when(mikrotik.query(any(), eq("connection-status"), eq("ether1"), any(EquipmentProvider.Connection.class)))
            .thenThrow(new IllegalStateException("API indisponível"));
        var first = controller.readings("map", Set.of(c.id())).getFirst();
        assertThat(first.a().up()).isTrue();
        assertThat(first.a().rxBps()).isEqualTo("1000");
        assertThat(first.b().error()).isNotNull();
        assertThat(controller.readings("map", Set.of(c.id()))).containsExactly(first);
        verify(mikrotik).query(any(), eq("connection"), eq("ether1"), any(EquipmentProvider.Connection.class));
        verify(mikrotik).query(any(), eq("connection-status"), eq("ether1"), any(EquipmentProvider.Connection.class));
        assertThat(controller.readings("map", Set.of("missing"))).isEmpty();
    }
    @Test void firstCounterSampleExpiresEarlyButMeasuredRatesKeepNormalCache() {
        equipment();
        var controller = new MappedConnectionController(jdbc, inventory, mikrotik, connections, hosts, datacom, mimosa, cisco);
        var c = controller.validate(new MappedConnectionController.Connection(null, "Rede", "a", "ether1", "b", "ether1"));
        persist(c);
        long now = System.currentTimeMillis();
        var firstRows = List.of(Map.of("running", "true", "rx-byte", "1000", "tx-byte", "2000"));
        var nextRows = List.of(Map.of("running", "true", "rx-byte", "2000", "tx-byte", "4000"));
        when(mikrotik.query(any(), eq("connection"), eq("ether1"), any(EquipmentProvider.Connection.class)))
            .thenReturn(new MikrotikService.Snapshot(now - 1500, firstRows, ""))
            .thenReturn(new MikrotikService.Snapshot(now, nextRows, ""));
        var first = controller.readings("map", Set.of(c.id())).getFirst();
        assertThat(first.a().txBps()).isNull();
        var second = controller.readings("map", Set.of(c.id())).getFirst();
        assertThat(second.a().txBps()).isEqualTo("10666");
        assertThat(second.a().rxBps()).isEqualTo("5333");
        assertThat(controller.readings("map", Set.of(c.id()))).containsExactly(second);
        verify(mikrotik, times(2)).query(any(), eq("connection"), eq("ether1"), any(EquipmentProvider.Connection.class));
    }
    @Test void supportsMixedDatacomAndMikrotikLinksWithoutClassifyingTheOltAsAnAp() {
        equipment();
        var controller = new MappedConnectionController(jdbc, inventory, mikrotik, connections, hosts, datacom, mimosa, cisco);
        when(hosts.credentials("a")).thenReturn(new br.com.scriptping.hosts.HostRepository.Credentials(
            "Datacom", "reader", "encrypted", 22, true, "ACTIVE", "", "10.0.0.1", false, 1L));
        String port = "ten-gigabit-ethernet-1/1/1";
        when(datacom.query(any(), eq("discover"), isNull(), any())).thenReturn(new EquipmentProvider.Snapshot(1,
            List.of(Map.of("name", port, "type", "ether")), ""));
        var c = controller.validate(new MappedConnectionController.Connection(null, "Uplink", "a", port, "b", "ether1"));
        persist(c);
        when(datacom.query(any(), eq("connection"), eq(port), any())).thenReturn(new EquipmentProvider.Snapshot(System.currentTimeMillis(),
            List.of(Map.of("running", "true", "disabled", "false", "rx-byte", "18446744073709551600", "tx-byte", "100", "rate", "10Gbps")), ""));
        when(mikrotik.query(any(), eq("connection-status"), eq("ether1"), any(EquipmentProvider.Connection.class)))
            .thenReturn(new MikrotikService.Snapshot(System.currentTimeMillis(), List.of(Map.of("running", "false")), ""));
        var reading = controller.readings("map", Set.of(c.id())).getFirst();
        assertThat(reading.a().up()).isTrue();
        assertThat(reading.a().rxBps()).isNull();
        assertThat(reading.a().capacityBps()).isEqualTo(10000000000L);
        assertThat(reading.b().up()).isFalse();
        assertThat(reading.b().error()).isNull();
        verify(datacom).query(any(), eq("discover"), isNull(), any());
    }
    @Test void discoversMimosaAndReadsMappedTrafficWithResetAndUnknownStatusProtection() {
        equipment();
        var controller = new MappedConnectionController(jdbc, inventory, mikrotik, connections, hosts, datacom, mimosa, cisco);
        when(hosts.credentials("a")).thenReturn(new br.com.scriptping.hosts.HostRepository.Credentials(
            "Mimosa", "configure", "encrypted", 443, true, "ACTIVE", "", "10.0.0.1", true, 1L));
        String port = "Ethernet (RJ45)";
        var ports = List.of(Map.of("name", port, "type", "ether", "connector", "electrical"));
        when(mimosa.query(any(), eq("discover"), isNull(), any())).thenReturn(new EquipmentProvider.Snapshot(1, ports, ""));
        when(mimosa.query(any(), eq("interfaces"), isNull(), any())).thenReturn(new EquipmentProvider.Snapshot(1, ports, ""));
        assertThat(controller.discover("a")).isEqualTo(ports);
        var c = controller.validate(new MappedConnectionController.Connection(null, "Radio-Core", "a", port, "b", "ether1"));
        persist(c);
        long now = System.currentTimeMillis();
        when(mimosa.query(any(), eq("connection"), eq(port), any()))
            .thenReturn(new EquipmentProvider.Snapshot(now - 1500, List.of(Map.of("running", "true", "rx-byte", "1000", "tx-byte", "2000", "counter-generation", "boot1")), ""))
            .thenReturn(new EquipmentProvider.Snapshot(now - 1500, List.of(Map.of("running", "true", "rx-byte", "2000", "tx-byte", "4000", "counter-generation", "boot2")), ""))
            .thenReturn(new EquipmentProvider.Snapshot(now, List.of(Map.of("running", "true", "rx-byte", "3000", "tx-byte", "6000", "counter-generation", "boot2")), ""));
        assertThat(controller.readings("map", Set.of(c.id())).getFirst().a().rxBps()).isNull();
        assertThat(controller.readings("map", Set.of(c.id())).getFirst().a().rxBps()).isNull();
        var measured = controller.readings("map", Set.of(c.id())).getFirst().a();
        assertThat(measured.rxBps()).isEqualTo("5333");
        assertThat(measured.txBps()).isEqualTo("10666");
        assertThat(measured.error()).isNull();
        var fresh = new MappedConnectionController(jdbc, inventory, mikrotik, connections, hosts, datacom, mimosa, cisco);
        when(mimosa.query(any(), eq("connection"), eq(port), any()))
            .thenReturn(new EquipmentProvider.Snapshot(now, List.of(Map.of("rx-byte", "3000", "tx-byte", "6000")), ""));
        var unknown = fresh.readings("map", Set.of(c.id())).getFirst().a();
        assertThat(unknown.error()).isNotNull();
        assertThat(unknown.rxBps()).isNull();
    }
    @Test void acceptsWirelessMapEndpointsAndMeasuredLinkRates() {
        equipment();
        when(hosts.credentials("a")).thenReturn(new br.com.scriptping.hosts.HostRepository.Credentials(
            "Mimosa", "configure", "encrypted", 443, true, "ACTIVE", "", "10.0.0.1", true, 1L));
        when(mimosa.query(any(), eq("interfaces"), isNull(), any())).thenReturn(new EquipmentProvider.Snapshot(1,
            List.of(Map.of("name", "Wireless", "type", "wireless", "connector", "wireless")), ""));
        var c = controller.validate(new MappedConnectionController.Connection(null, "PTP", "a", "Wireless", "b", "ether1"));
        persist(c);
        when(mimosa.query(any(), eq("connection"), eq("Wireless"), any())).thenReturn(new EquipmentProvider.Snapshot(System.currentTimeMillis(),
            List.of(Map.of("running", "true", "rx-bits-per-second", "204", "tx-bits-per-second", "100")), ""));
        var endpoint = controller.readings("map", Set.of(c.id())).getFirst().a();
        assertThat(endpoint.up()).isTrue();
        assertThat(endpoint.rxBps()).isEqualTo("204");
        assertThat(endpoint.txBps()).isEqualTo("100");
        assertThat(endpoint.capacityBps()).isNull();
    }
    @Test void slowInterfaceDoesNotBlockAnotherConnection() throws Exception {
        equipment();
        var database = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var reader = new MappedConnectionController(database, inventory, mikrotik, connections, hosts, datacom, mimosa, cisco);
        var slow = new MappedConnectionController.Connection("slow", "Slow", "a", "slow", "b", "slow");
        var fast = new MappedConnectionController.Connection("fast", "Fast", "a", "fast", "b", "fast");
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var links = List.of(slow, fast).stream().map(c -> Map.of("from", "a", "to", "b", "connection", c)).toList();
        when(database.queryForList(anyString(), eq(String.class), eq("map")))
            .thenReturn(List.of(json.writeValueAsString(Map.of("nodes", List.of(), "links", links))));
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        when(mikrotik.query(any(), eq("connection"), anyString(), any(EquipmentProvider.Connection.class)))
            .thenAnswer(call -> {
                if ("slow".equals(call.getArgument(2))) {
                    entered.countDown();
                    if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Timed out");
                }
                return new MikrotikService.Snapshot(System.currentTimeMillis(), List.of(Map.of("running", "true",
                    "rx-bits-per-second", "1000", "tx-bits-per-second", "2000")), "");
            });
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = executor.submit(() -> reader.readings("map", Set.of("slow")));
            try {
                assertThat(entered.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                var independent = executor.submit(() -> reader.readings("map", Set.of("fast")));
                var reading = independent.get(2, java.util.concurrent.TimeUnit.SECONDS).getFirst();
                assertThat(reading.id()).isEqualTo("fast");
                assertThat(reading.a().rxBps()).isEqualTo("1000");
                assertThat(pending.isDone()).isFalse();
            } finally { release.countDown(); }
            assertThat(pending.get(2, java.util.concurrent.TimeUnit.SECONDS)).hasSize(1);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"MikroTik", "Datacom", "Mimosa"})
    void destinationReadsOnlyStatusForEveryManufacturer(String manufacturer) {
        equipment();
        when(hosts.credentials("b")).thenReturn(new br.com.scriptping.hosts.HostRepository.Credentials(
            manufacturer, "reader", "encrypted", 443, true, "ACTIVE", "", "10.0.0.2", true, 1L));
        var c = new MappedConnectionController.Connection("link", "Link", "a", "ether1", "b", "port");
        persist(c);
        var destinationRows = List.of(Map.of("running", "false", "rx-bits-per-second", "999", "tx-bits-per-second", "999"));
        when(mikrotik.query(any(), eq("connection"), eq("ether1"), any(EquipmentProvider.Connection.class)))
            .thenAnswer(call -> new MikrotikService.Snapshot(System.currentTimeMillis(), List.of(Map.of("running", "true", "rx-bits-per-second", "1000", "tx-bits-per-second", "2000")), ""));
        when(mikrotik.query(any(), eq("connection-status"), eq("port"), any(EquipmentProvider.Connection.class)))
            .thenAnswer(call -> new MikrotikService.Snapshot(System.currentTimeMillis(), destinationRows, ""));
        when(datacom.query(any(), eq("connection-status"), eq("port"), any()))
            .thenAnswer(call -> new EquipmentProvider.Snapshot(System.currentTimeMillis(), destinationRows, ""));
        when(mimosa.query(any(), eq("connection-status"), eq("port"), any()))
            .thenAnswer(call -> new EquipmentProvider.Snapshot(System.currentTimeMillis(), destinationRows, ""));
        var reading = controller.readings("map", Set.of(c.id())).getFirst();
        assertThat(reading.a().txBps()).isEqualTo("2000");
        assertThat(reading.b().up()).isFalse();
        assertThat(reading.b().error()).isNull();
        assertThat(reading.b().rxBps()).isNull();
        assertThat(reading.b().txBps()).isNull();
        verify(mikrotik, never()).query(eq(hosts.target("b")), eq("connection"), anyString(), any(EquipmentProvider.Connection.class));
        verify(datacom, never()).query(any(), eq("connection"), anyString(), any());
        verify(mimosa, never()).query(any(), eq("connection"), anyString(), any());
    }

    @Test void statusCacheDoesNotReplaceTrafficWhenTheSameInterfaceIsAlsoAnOrigin() throws Exception {
        equipment();
        var forward = new MappedConnectionController.Connection("forward", "Forward", "a", "ether1", "b", "ether1");
        var reverse = new MappedConnectionController.Connection("reverse", "Reverse", "b", "ether1", "a", "ether1");
        persist(forward);
        var links = List.of(forward, reverse).stream().map(c -> Map.of("from", "a", "to", "b", "connection", c)).toList();
        jdbc.update("UPDATE network_map SET layout=? WHERE id='map'", new com.fasterxml.jackson.databind.ObjectMapper()
            .writeValueAsString(Map.of("nodes", List.of(), "links", links)));
        when(mikrotik.query(any(), eq("connection"), eq("ether1"), any(EquipmentProvider.Connection.class)))
            .thenAnswer(call -> new MikrotikService.Snapshot(System.currentTimeMillis(), List.of(Map.of("running", "true", "rx-bits-per-second", "1000", "tx-bits-per-second", "2000")), ""));
        var readings = controller.readings("map", Set.of("forward", "reverse"));
        assertThat(readings).hasSize(2).allSatisfy(reading -> {
            assertThat(reading.a().txBps()).isEqualTo("2000");
            assertThat(reading.b().up()).isTrue();
            assertThat(reading.b().txBps()).isNull();
        });
        var cached = controller.readings("map", Set.of("forward", "reverse"));
        assertThat(cached).extracting(MappedConnectionController.Reading::a)
            .containsExactlyElementsOf(readings.stream().map(MappedConnectionController.Reading::a).toList());
        assertThat(cached.getFirst().b().measuredAt()).isEqualTo(readings.get(1).a().measuredAt());
        assertThat(cached).allSatisfy(reading -> {
            assertThat(reading.b().up()).isTrue();
            assertThat(reading.b().txBps()).isNull();
        });
        verify(mikrotik, times(2)).query(any(), eq("connection"), eq("ether1"), any(EquipmentProvider.Connection.class));
        verify(mikrotik).query(any(), eq("connection-status"), eq("ether1"), any(EquipmentProvider.Connection.class));
    }

    void persist(MappedConnectionController.Connection connection) {
        try {
            var json = new com.fasterxml.jackson.databind.ObjectMapper();
            var link = Map.of("from", "a", "to", "b", "mappedConnectionId", connection.id(), "connection", connection);
            jdbc.update("INSERT INTO network_map(id,revision,layout) VALUES ('map',1,?)",
                json.writeValueAsString(Map.of("name", "Rede", "nodes", List.of(), "links", List.of(link))));
        } catch (Exception ex) { throw new IllegalStateException(ex); }
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
