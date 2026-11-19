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
    public MimosaHttps(@Value("${mimosa.known-certificates-directory:./data/mimosa-certificates}") String directory) {
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
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
        byte[] form = ("username=configure&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
        request(origin.resolve("/login.php"), context, cookies, form, address);
        byte[] dashboard = request(origin.resolve("/cgi/dashboard.php"), context, cookies, null, address);
        if (!wirelessTraffic) return dashboard;
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var root = json.readTree(dashboard);
        try {
            var traffic = json.readTree(request(origin.resolve("/core/api/calls/TPutPer.php"), context, cookies, null, address));
            if (root instanceof com.fasterxml.jackson.databind.node.ObjectNode object) object.set("wirelessTraffic", traffic);
        } catch (Exception unavailable) { /* Link status remains valid without traffic. */ }
        return json.writeValueAsBytes(root);
    }
    private byte[] request(URI uri, SSLContext context, CookieManager cookies, byte[] form, String address) throws Exception {
        var connection = (HttpsURLConnection) uri.toURL().openConnection();
        connection.setSSLSocketFactory(context.getSocketFactory());
        connection.setHostnameVerifier((hostname, session) -> {
            try {
                if (!hostname.equalsIgnoreCase(address)) return false;
                verify(uri.getRawAuthority(), (X509Certificate) session.getPeerCertificates()[0]);
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
            if (form != null && (status == 302 || status == 303)) return new byte[0];
            if (status != 200) throw new IllegalArgumentException("Mimosa HTTP " + status);
            try (var input = connection.getInputStream()) {
                byte[] body = input.readNBytes(2 * 1024 * 1024 + 1);
                if (body.length > 2 * 1024 * 1024) throw new IllegalArgumentException("Oversized dashboard");
                return body;
            }
        } finally { connection.disconnect(); }
    }
}
