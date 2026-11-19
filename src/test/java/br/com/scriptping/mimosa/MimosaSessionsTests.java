package br.com.scriptping.mimosa;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class MimosaSessionsTests {
    @TempDir Path directory;
    @FunctionalInterface interface Response { byte[] read(URI uri, MimosaHttps.Session session) throws Exception; }
    static class FakeHttps extends MimosaHttps {
        Response response;
        FakeHttps(String directory) { super(directory); }
        @Override byte[] request(URI uri, Session session, byte[] form, String address) throws Exception {
            return response.read(uri, session);
        }
    }

    @Test void reusesAuthenticationReplacesChangedPasswordAndRefusesReadsAfterClose() throws Exception {
        var https = new FakeHttps(directory.toString());
        var logins = new AtomicInteger();
        https.response = (uri, session) -> {
            if (uri.getPath().equals("/login.php")) {
                logins.incrementAndGet();
                return new byte[0];
            }
            return "{\"realtime\":{\"device_info\":{\"Model\":\"B5c\"}}}".getBytes(StandardCharsets.UTF_8);
        };
        https.mappedDashboard("1", "192.0.2.1", 443, "first", false);
        https.mappedDashboard("1", "192.0.2.1", 443, "first", false);
        assertEquals(1, logins.get());
        https.mappedDashboard("1", "192.0.2.1", 443, "second", false);
        assertEquals(2, logins.get());
        https.close();
        assertThrows(java.io.IOException.class,
            () -> https.mappedDashboard("1", "192.0.2.1", 443, "second", false));
        assertEquals(2, logins.get());
    }

    @Test void expiredAuthenticationRetriesOnceAndWirelessUsesSameSession() throws Exception {
        var https = new FakeHttps(directory.toString());
        var logins = new AtomicInteger();
        var dashboards = new AtomicInteger();
        var cookies = new java.util.concurrent.atomic.AtomicReference<Object>();
        https.response = (uri, session) -> {
            if (uri.getPath().equals("/login.php")) {
                logins.incrementAndGet(); cookies.set(session);
                return new byte[0];
            }
            assertSame(cookies.get(), session);
            if (uri.getPath().endsWith("TPutPer.php"))
                return "{\"tput1min\":\"100:12,34;\"}".getBytes(StandardCharsets.UTF_8);
            if (dashboards.incrementAndGet() == 2) return "{}".getBytes(StandardCharsets.UTF_8);
            return "{\"realtime\":{\"device_info\":{\"Model\":\"B5c\"}}}".getBytes(StandardCharsets.UTF_8);
        };
        https.mappedDashboard("1", "192.0.2.1", 443, "password", true);
        String result = new String(https.mappedDashboard("1", "192.0.2.1", 443, "password", true), StandardCharsets.UTF_8);
        assertEquals(2, logins.get());
        assertTrue(result.contains("100:12,34;"));
    }
    @Test void tenMixedReadsReuseAuthenticationCookiesAndTlsFactory() throws Exception {
        var https = new FakeHttps(directory.toString());
        var logins = new AtomicInteger();
        var trafficCalls = new AtomicInteger();
        var shared = new java.util.concurrent.atomic.AtomicReference<MimosaHttps.Session>();
        var sockets = new java.util.concurrent.atomic.AtomicReference<javax.net.ssl.SSLSocketFactory>();
        https.response = (uri, session) -> {
            if (uri.getPath().equals("/login.php")) {
                logins.incrementAndGet(); shared.set(session); sockets.set(session.sockets);
                return new byte[0];
            }
            assertSame(shared.get(), session);
            assertSame(sockets.get(), session.sockets);
            if (uri.getPath().endsWith("TPutPer.php")) {
                trafficCalls.incrementAndGet();
                return "{\"tput1min\":\"100:12,34;\"}".getBytes(StandardCharsets.UTF_8);
            }
            return "{\"realtime\":{\"device_info\":{\"Model\":\"B5c\"}}}".getBytes(StandardCharsets.UTF_8);
        };
        try {
            for (int i = 0; i < 10; i++) https.mappedDashboard("1", "192.0.2.1", 443, "password", i % 2 == 0);
            assertEquals(1, logins.get());
            assertEquals(5, trafficCalls.get());
        } finally { https.close(); }
    }

}
