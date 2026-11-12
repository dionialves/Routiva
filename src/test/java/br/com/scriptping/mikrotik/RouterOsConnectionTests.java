package br.com.scriptping.mikrotik;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class RouterOsConnectionTests {
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
            var service = new MikrotikService("viewer", "secret", server.getLocalPort(), false);
            var host = new br.com.scriptping.zabbix.Host("1", "Router", "127.0.0.1", List.of());
            var row = service.query(host, "interfaces", null).rows().getFirst();
            assertEquals("ether", row.get("type"));
            assertEquals("true", row.get("running"));
            assertEquals("12000", row.get("rx-byte"));
            assertEquals("6000", row.get("tx-byte"));
            assertEquals("2", row.get("tx-error"));
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
