package br.com.scriptping.mimosa;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.cert.*;
import java.util.*;
import javax.net.ssl.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Device-scoped trust on first use, like SSH known hosts. Never changes global TLS settings. */
@Component
public class MimosaHttps {
    private final Path directory;
    public MimosaHttps(@Value("${mimosa.known-certificates-directory:${MIMOSA_KNOWN_CERTIFICATES_DIRECTORY:./data/mimosa-certificates}}") String directory) {
        this.directory = Path.of(directory);
    }
    synchronized void verify(String authority, X509Certificate certificate) throws Exception {
        String filename = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(authority.getBytes(StandardCharsets.UTF_8)));
        byte[] fingerprint = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
        Files.createDirectories(directory);
        Path path = directory.resolve(filename + ".sha256");
        try { Files.write(path, fingerprint, StandardOpenOption.CREATE_NEW); }
        catch (FileAlreadyExistsException existing) { /* Verify the first certificate, including across restarts. */ }
        if (!MessageDigest.isEqual(Files.readAllBytes(path), fingerprint))
            throw new CertificateException("O certificado da Mimosa mudou. Confira o equipamento antes de renovar a confiança.");
    }
    public byte[] dashboard(String address, int port, String password) throws Exception {
        return dashboard(address, port, password, false);
    }
    public byte[] dashboard(String address, int port, String password, boolean wirelessTraffic) throws Exception {
        try (var session = open(address, port, password)) {
            return dashboard(session, wirelessTraffic);
        }
    }
    static final class Session implements AutoCloseable {
        final URI origin;
        final SSLSocketFactory sockets;
        final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
        HttpsURLConnection lastRequest;
        Session(URI origin, SSLContext context) {
            this.origin = origin; this.sockets = context.getSocketFactory();
        }
        @Override public void close() {
            cookies.getCookieStore().removeAll();
            if (lastRequest != null) lastRequest.disconnect();
        }
    }
    private final br.com.scriptping.equipment.MappedEquipmentSessions<Session> sessions =
        new br.com.scriptping.equipment.MappedEquipmentSessions<>(
            (host, credentials) -> open(host.ip(), credentials.port(), credentials.password()),
            ex -> !(ex instanceof CertificateException));
    public byte[] mappedDashboard(String hostId, String address, int port, String password, boolean wirelessTraffic) throws Exception {
        var host = new br.com.scriptping.zabbix.Host(hostId, "", address, List.of());
        var credentials = new br.com.scriptping.equipment.EquipmentProvider.Connection("configure", password, port, true);
        return sessions.read(host, credentials, session -> dashboard(session, wirelessTraffic));
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 10000)
    public void expireIdle() { sessions.expireIdle(); }
    @jakarta.annotation.PreDestroy
    public void close() { sessions.close(); }
    private Session open(String address, int port, String password) throws Exception {
        var origin = new URI("https", null, address, port, "/", null, null);
        String authority = origin.getRawAuthority();
        var context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            public void checkClientTrusted(X509Certificate[] chain, String auth) throws CertificateException { throw new CertificateException("Client certificate unsupported"); }
            public void checkServerTrusted(X509Certificate[] chain, String auth) throws CertificateException {
                try {
                    if (chain == null || chain.length == 0) throw new CertificateException("Missing certificate");
                    verify(authority, chain[0]);
                } catch (CertificateException ex) { throw ex;
                } catch (Exception ex) { throw new CertificateException("Não foi possível verificar o certificado Mimosa.", ex); }
            }
        }}, null);
        var session = new Session(origin, context);
        byte[] form = ("username=configure&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
        try {
            request(origin.resolve("/login.php"), session, form, address);
            return session;
        } catch (Exception ex) { session.close(); throw ex; }
    }
    private byte[] dashboard(Session session, boolean wirelessTraffic) throws Exception {
        var origin = session.origin;
        String address = origin.getHost();
        byte[] dashboard = request(origin.resolve("/cgi/dashboard.php"), session, null, address);
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var root = json.readTree(dashboard);
        if (!root.path("realtime").path("device_info").hasNonNull("Model"))
            throw new IllegalArgumentException("Invalid dashboard or expired session");
        if (!wirelessTraffic) return dashboard;
        try {
            var traffic = json.readTree(request(origin.resolve("/core/api/calls/TPutPer.php"), session, null, address));
            if (root instanceof com.fasterxml.jackson.databind.node.ObjectNode object) object.set("wirelessTraffic", traffic);
        } catch (Exception unavailable) {
            if (root instanceof com.fasterxml.jackson.databind.node.ObjectNode object)
                object.put("wirelessTrafficUnavailable", true);
        }
        return json.writeValueAsBytes(root);
    }
    byte[] request(URI uri, Session session, byte[] form, String address) throws Exception {
        var connection = (HttpsURLConnection) uri.toURL().openConnection();
        session.lastRequest = connection;
        var cookies = session.cookies;
        connection.setSSLSocketFactory(session.sockets);
        connection.setHostnameVerifier((hostname, tlsSession) -> {
            try {
                if (!hostname.equalsIgnoreCase(address)) return false;
                verify(uri.getRawAuthority(), (X509Certificate) tlsSession.getPeerCertificates()[0]);
                return true;
            } catch (Exception ex) { return false; }
        });
        connection.setConnectTimeout(5000); connection.setReadTimeout(10000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", "application/json");
        cookies.get(uri, Map.of()).forEach((key, values) -> connection.setRequestProperty(key, String.join("; ", values)));
        try {
            if (form != null) {
                connection.setRequestMethod("POST"); connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                try (var output = connection.getOutputStream()) { output.write(form); }
            }
            int status = connection.getResponseCode();
            cookies.put(uri, connection.getHeaderFields());
            if (status != 200 && !(form != null && (status == 302 || status == 303))) throw new IllegalArgumentException("Mimosa HTTP " + status);
            try (var input = connection.getInputStream()) {
                byte[] body = input.readNBytes(2 * 1024 * 1024 + 1);
                if (body.length > 2 * 1024 * 1024) throw new IllegalArgumentException("Oversized dashboard");
                return body;
            }
        } catch (Exception ex) { connection.disconnect(); throw ex; }
    }
}
