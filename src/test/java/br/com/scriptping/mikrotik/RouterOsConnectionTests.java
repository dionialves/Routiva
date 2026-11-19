package br.com.scriptping.mikrotik;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class RouterOsConnectionTests {
    @Test void discoversPhysicalInterfacesAndMonitorsEachPortInOneSession() throws Exception {
        try (var server = new ServerSocket(0); var executor = Executors.newSingleThreadExecutor()) {
            var router = executor.submit(() -> {
                try (var socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    var in = socket.getInputStream(); var out = socket.getOutputStream();
                    assertEquals("/login", read(in).getFirst()); send(out, "!done");
                    var discovery = read(in);
                    assertEquals("/interface/ethernet/print", discovery.getFirst());
                    assertTrue(discovery.stream().anyMatch(word -> word.startsWith("=.proplist=") && word.contains("advertise")));
                    send(out, "!re", "=.id=*1", "=name=uplink", "=default-name=sfp-sfpplus1", "=comment=Core", "=type=ether", "=running=true");
                    send(out, "!re", "=.id=*2", "=name=ether7", "=default-name=ether7", "=type=ether", "=running=false", "=advertise=10M-baseT-half,100M-baseT-full,1G-baseT-full");
                    send(out, "!done");
                    for (int i = 1; i <= 2; i++) {
                        var monitor = read(in);
                        assertEquals("/interface/ethernet/monitor", monitor.getFirst());
                        assertTrue(monitor.contains("=numbers=*" + i));
                        assertTrue(monitor.contains("=once="));
                        send(out, "!re", "=status=" + (i == 1 ? "link-ok" : "no-link"), "=rate=" + (i == 1 ? "10Gbps" : ""),
                            "=supported=" + (i == 1 ? "1G-full,10G-full,25G-full" : ""), "=advertising=");
                        send(out, "!done");
                    }
                } catch (IOException ex) { throw new UncheckedIOException(ex); }
            });
            var service = new MikrotikService(null);
            var host = new br.com.scriptping.zabbix.Host("1", "Router", "127.0.0.1", List.of());
            var rows = service.query(host, "discover", null,
                new br.com.scriptping.equipment.EquipmentProvider.Connection("viewer", "test-only", server.getLocalPort()), false).rows();
            assertEquals(2, rows.size());
            assertEquals("uplink", rows.getFirst().get("name"));
            assertEquals("Core", rows.getFirst().get("comment"));
            assertEquals("10Gbps", rows.getFirst().get("rate"));
            assertEquals("1G-full,10G-full,25G-full", rows.getFirst().get("supported"));
            assertEquals("10M-baseT-half,100M-baseT-full,1G-baseT-full", rows.getLast().get("advertising"));
            assertEquals("link-ok", rows.getFirst().get("status"));
            assertEquals("no-link", rows.getLast().get("status"));
            router.get(5, TimeUnit.SECONDS);
        }
    }

    @Test void interfacesReadStatisticsAlongsideInventory() throws Exception {
        try (var server = new ServerSocket(0); var executor = Executors.newSingleThreadExecutor()) {
            var router = executor.submit(() -> {
                try (var socket = server.accept()) {
                    var in = socket.getInputStream(); var out = socket.getOutputStream();
                    read(in); send(out, "!done");
                    var inventory = read(in);
                    assertEquals("/interface/print", inventory.getFirst());
                    assertFalse(inventory.contains("=stats-detail="));
                    send(out, "!re", "=.id=*1", "=name=ether1", "=type=ether", "=running=true");
                    send(out, "!done");
                    var stats = read(in);
                    assertEquals("/interface/print", stats.getFirst());
                    assertTrue(stats.contains("=stats-detail="));
                    send(out, "!re", "=.id=*1", "=name=ether1", "=rx-byte=12000", "=tx-byte=6000", "=rx-error=0", "=tx-error=2");
                    send(out, "!done");
                } catch (IOException ex) { throw new UncheckedIOException(ex); }
            });
            var service = new MikrotikService(null);
            var host = new br.com.scriptping.zabbix.Host("1", "Router", "127.0.0.1", List.of());
            var row = service.query(host, "interfaces", null, new br.com.scriptping.equipment.EquipmentProvider.Connection("viewer", "secret", server.getLocalPort()), false).rows().getFirst();
            assertEquals("ether", row.get("type"));
            assertEquals("true", row.get("running"));
            assertEquals("12000", row.get("rx-byte"));
            assertEquals("6000", row.get("tx-byte"));
            assertEquals("2", row.get("tx-error"));
            router.get(5, TimeUnit.SECONDS);
        }
    }

    @Test void mappedInterfacesReuseOneLoginAndResolveInterfaceIdsOnEveryCollection() throws Exception {
        try (var server = new ServerSocket(0); var executor = Executors.newSingleThreadExecutor()) {
            var router = executor.submit(() -> {
                try (var socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    var in = socket.getInputStream(); var out = socket.getOutputStream();
                    assertEquals("/login", read(in).getFirst()); send(out, "!done");
                    for (int collection = 1; collection <= 3; collection++) {
                        assertEquals("/interface/print", read(in).getFirst());
                        send(out, "!re", "=.id=*" + collection, "=name=ether" + collection, "=type=ether", "=running=true");
                        send(out, "!done");
                        if (collection == 3) continue; // Status-only: no counters or monitor command.
                        var stats = read(in);
                        assertTrue(stats.contains("=stats-detail="));
                        assertTrue(stats.contains("?name=ether" + collection));
                        send(out, "!re", "=rx-byte=12000", "=tx-byte=6000"); send(out, "!done");
                        var status = read(in);
                        assertEquals("/interface/ethernet/monitor", status.getFirst());
                        assertTrue(status.contains("=numbers=*" + collection));
                        send(out, "!re", "=rate=1Gbps"); send(out, "!done");
                    }
                    assertEquals(-1, in.read());
                } catch (IOException ex) { throw new UncheckedIOException(ex); }
            });
            var service = new MikrotikService(null);
            var host = new br.com.scriptping.zabbix.Host("1", "Router", "127.0.0.1", List.of());
            var credentials = new br.com.scriptping.equipment.EquipmentProvider.Connection("viewer", "test-only", server.getLocalPort());
            try {
                for (int i = 1; i <= 3; i++) {
                    var row = service.query(host, i == 3 ? "connection-status" : "connection", "ether" + i, credentials, false).rows().getFirst();
                    if (i == 3) {
                        assertEquals("true", row.get("running"));
                        assertFalse(row.containsKey("rx-byte"));
                        assertFalse(row.containsKey("tx-byte"));
                    } else {
                        assertEquals("12000", row.get("rx-byte"));
                        assertEquals("1Gbps", row.get("rate"));
                    }
                }
            } finally { service.closeMappedSessions(); }
            router.get(5, TimeUnit.SECONDS);
        }
    }

    @Test void lengthsRoundTripAtEveryBoundary() throws Exception {
        for (int length : new int[]{0, 1, 127, 128, 16383, 16384, 2097151, 2097152, 268435455, 268435456, Integer.MAX_VALUE}) {
            var bytes = new ByteArrayOutputStream();
            RouterOsConnection.writeLength(bytes, length);
            assertEquals(length, RouterOsConnection.readLength(new ByteArrayInputStream(bytes.toByteArray())));
        }
        assertThrows(EOFException.class, () -> RouterOsConnection.readLength(new ByteArrayInputStream(new byte[]{(byte) 0x80})));
        assertThrows(IOException.class, () -> RouterOsConnection.readLength(new ByteArrayInputStream(new byte[]{(byte) 0xf8})));
    }

    @Test void authenticatesReadsUnicodeAndDrainsTrapsBeforeNextCommand() throws Exception {
        try (var server = new ServerSocket(0); var executor = Executors.newSingleThreadExecutor()) {
            var router = executor.submit(() -> {
                try (var socket = server.accept()) {
                    var in = socket.getInputStream(); var out = socket.getOutputStream();
                    assertEquals(List.of("/login", "=name=viewer", "=password=secret"), read(in));
                    send(out, "!done");
                    assertEquals(List.of("/system/identity/print"), read(in));
                    send(out, "!re", "=name=Rádio São Paulo"); send(out, "!done");
                    assertEquals(List.of("/interface/wifi/registration-table/print"), read(in));
                    send(out, "!trap", "=message=no such command"); send(out, "!done");
                    assertEquals(List.of("/interface/print"), read(in));
                    send(out, "!re", "=.id=*1", "=name=ether1"); send(out, "!done");
                } catch (IOException ex) { throw new UncheckedIOException(ex); }
            });
            try (var api = new RouterOsConnection("127.0.0.1", server.getLocalPort(), false, "viewer", "secret")) {
                assertEquals("Rádio São Paulo", api.command("/system/identity/print").getFirst().get("name"));
                assertThrows(RouterOsConnection.CommandRejectedException.class, () -> api.command("/interface/wifi/registration-table/print"));
                assertEquals("ether1", api.command("/interface/print").getFirst().get("name"));
            }
            router.get(5, TimeUnit.SECONDS);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "6.49.18 (long-term),/interface/wireless",
        "7.12.2 (stable),/interface/wifiwave2",
        "7.13 (stable),/interface/wifi",
        "7.20.1 (stable),/interface/wifi"
    })
    void selectsRadioCommandsFromDeviceVersion(String version, String menu) throws Exception {
        try (var server = new ServerSocket(0); var executor = Executors.newSingleThreadExecutor()) {
            var router = executor.submit(() -> {
                try (var socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    var in = socket.getInputStream(); var out = socket.getOutputStream();
                    assertEquals("/login", read(in).getFirst()); send(out, "!done");
                    assertEquals("/system/resource/print", read(in).getFirst());
                    send(out, "!re", "=version=" + version); send(out, "!done");
                    assertEquals("/interface/wireless/registration-table/print", read(in).getFirst());
                    if (!menu.endsWith("/wireless")) {
                        send(out, "!trap", "=message=no such command prefix"); send(out, "!done");
                        assertEquals(menu + "/registration-table/print", read(in).getFirst());
                    }
                    send(out, "!re", "=interface=wlan1", "=mac-address=00:11:22:33:44:55",
                        "=signal-strength=-65", "=tx-rate=866.6Mbps", "=rx-bytes=1234");
                    send(out, "!done");
                    assertEquals(-1, in.read());
                } catch (IOException ex) { throw new UncheckedIOException(ex); }
            });
            var snapshot = new MikrotikService(null).query(
                new br.com.scriptping.zabbix.Host("1", "Router", "127.0.0.1", List.of()), "wireless", null,
                new br.com.scriptping.equipment.EquipmentProvider.Connection("viewer", "secret", server.getLocalPort()), false);
            assertEquals("", snapshot.warning());
            assertEquals(1, snapshot.rows().size());
            var row = snapshot.rows().getFirst();
            assertEquals("-65", row.get("signal-strength"));
            assertEquals("1234", row.get("rx-bytes"));
            assertFalse(row.containsKey("tx-ccq"));
            router.get(5, TimeUnit.SECONDS);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "no such command prefix,pacote ausente",
        "not enough permissions (9),Verifique permissões"
    })
    void reportsUnavailableRadioWithoutInventingMeasurements(String rejection, String expected) throws Exception {
        try (var server = new ServerSocket(0); var executor = Executors.newSingleThreadExecutor()) {
            var router = executor.submit(() -> {
                try (var socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    var in = socket.getInputStream(); var out = socket.getOutputStream();
                    read(in); send(out, "!done");
                    assertEquals("/system/resource/print", read(in).getFirst());
                    send(out, "!re", "=version=7.20 (stable)"); send(out, "!done");
                    for (String menu : List.of("wireless", "wifi")) {
                        assertEquals("/interface/" + menu + "/registration-table/print", read(in).getFirst());
                        send(out, "!trap", "=message=" + rejection); send(out, "!done");
                    }
                } catch (IOException ex) { throw new UncheckedIOException(ex); }
            });
            var snapshot = new MikrotikService(null).query(
                new br.com.scriptping.zabbix.Host("1", "Router", "127.0.0.1", List.of()), "wireless", null,
                new br.com.scriptping.equipment.EquipmentProvider.Connection("viewer", "secret", server.getLocalPort()), false);
            assertTrue(snapshot.rows().isEmpty());
            assertTrue(snapshot.warning().contains(expected));
            router.get(5, TimeUnit.SECONDS);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"6.49.18,wireless", "7.12.2,wifiwave2", "7.20,wifi"})
    void bindsUsingDetectedVersionAndReadsRadioOverview(String version, String menu) throws Exception {
        try (var server = new ServerSocket(0); var executor = Executors.newSingleThreadExecutor()) {
            var router = executor.submit(() -> {
                try (var socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    var in = socket.getInputStream(); var out = socket.getOutputStream();
                    read(in); send(out, "!done");
                    assertEquals("/system/resource/print", read(in).getFirst());
                    send(out, "!re", "=version=" + version); send(out, "!done");
                    assertEquals("/system/identity/print", read(in).getFirst());
                    send(out, "!re", "=name=Router"); send(out, "!done");
                    var menus = version.startsWith("6.") ? List.of("wireless") : List.of("wireless", menu);
                    for (String current : menus) {
                        assertEquals("/interface/" + current + "/print", read(in).getFirst());
                        if (current.equals(menu)) {
                            send(out, "!re", "=configuration.ssid=Office", "=channel.frequency=5180", "=channel.width=20mhz");
                        } else send(out, "!trap", "=message=no such command prefix");
                        send(out, "!done");
                    }
                } catch (IOException ex) { throw new UncheckedIOException(ex); }
            });
            assertDoesNotThrow(() -> new MikrotikService(null).testConnection(
                new br.com.scriptping.zabbix.Host("1", "Router", "127.0.0.1", List.of()),
                new br.com.scriptping.equipment.EquipmentProvider.Connection("viewer", "secret", server.getLocalPort())));
            router.get(5, TimeUnit.SECONDS);
        }
    }

    @Test void distinguishesMissingPackagesFromPermissionErrors() {
        assertTrue(new RouterOsConnection.CommandRejectedException("no such command prefix").missingCommand());
        assertFalse(new RouterOsConnection.CommandRejectedException("not enough permissions (9)").missingCommand());
    }

    private static List<String> read(InputStream in) throws IOException {
        var words = new ArrayList<String>();
        for (int length; (length = RouterOsConnection.readLength(in)) != 0; )
            words.add(new String(in.readNBytes(length), StandardCharsets.UTF_8));
        return words;
    }
    private static void send(OutputStream out, String... words) throws IOException {
        for (String word : words) { byte[] bytes = word.getBytes(StandardCharsets.UTF_8); RouterOsConnection.writeLength(out, bytes.length); out.write(bytes); }
        out.write(0); out.flush();
    }
}
