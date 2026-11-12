package br.com.scriptping.ixc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class IxcClient {
    private final String url, token;
    private final ObjectMapper mapper;
    private final HttpClient http;

    @org.springframework.beans.factory.annotation.Autowired
    public IxcClient(@Value("${IXC_URL:}") String url, @Value("${IXC_TOKEN:}") String token,
            ObjectMapper mapper) {
        this(url, token, mapper,
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    IxcClient(String url, String token, ObjectMapper mapper, HttpClient http) {
        this.http = http;
        this.url = url.replaceAll("/+$", ""); this.token = token;
        this.mapper = mapper;
    }

    // Do not include credentials in generated toString/log output.
    public static final class Login {
        public final String id, mac, ip, pppoe, pppoePassword, equipmentPassword, connectionPort, planId;
        Login(JsonNode row, String mac) {
            this.planId = field(row, "id_grupo");
            this.id = field(row, "id"); this.mac = mac; this.ip = field(row, "ip");
            this.pppoe = field(row, "login"); this.pppoePassword = field(row, "senha");
            this.equipmentPassword = field(row, "senha_router1"); this.connectionPort = field(row, "porta_http");
        }
        private static String field(JsonNode row, String key) { return row.path(key).asText(""); }
    }

    public static String normalizeMac(String value) {
        if (value == null || !value.matches("(?i)([0-9a-f]{12}|([0-9a-f]{2}:){5}[0-9a-f]{2}|([0-9a-f]{2}-){5}[0-9a-f]{2}|([0-9a-f]{4}\\.){2}[0-9a-f]{4})"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "MAC inválido.");
        String hex = value.replaceAll("[:.\\-]", "").toUpperCase(Locale.ROOT);
        return hex.replaceAll("(..)(?!$)", "$1:");
    }

    public List<Login> findByMac(String value) {
        return findByMacs(List.of(value));
    }

    public List<Login> findByMacs(Collection<String> values) {
        var macs = values.stream().map(IxcClient::normalizeMac).distinct().toList();
        if (macs.isEmpty()) return List.of();
        if (url.isBlank() || token.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
            "Configure IXC_URL e IXC_TOKEN no .env.");
        try {
            URI endpoint = URI.create((url.endsWith("/webservice/v1") ? url : url + "/webservice/v1") + "/radusuarios");
            if (!"https".equalsIgnoreCase(endpoint.getScheme()) || endpoint.getHost() == null || endpoint.getUserInfo() != null)
                throw new IllegalArgumentException();
            var result = new LinkedHashMap<String, Login>();
            var allowed = new HashSet<>(macs);
            boolean single = macs.size() == 1;
            int pageSize = 1000;
            int received = 0;
            for (int page = 1; ; page++) {
                // IXC documents IN for numbers only. Read a shared inventory for multiple MACs.
                var body = Map.of("qtype", single ? "radusuarios.mac" : "radusuarios.id",
                    "query", single ? macs.getFirst() : "0", "oper", single ? "=" : ">",
                    "page", Integer.toString(page), "rp", Integer.toString(pageSize),
                    "sortname", "radusuarios.id", "sortorder", "asc");
                var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8)))
                    .header("ixcsoft", "listar").header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
                var response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 200 || response.statusCode() >= 300) throw unavailable();
                var root = readPage(response.body());
                for (var login : parseRows(root, allowed)) result.put(login.mac + ":" + login.id, login);
                received += root.path("registros").size();
                if (received >= root.path("total").asInt()) break;
                if (page >= 100 || root.path("registros").isEmpty()) throw unavailable();
            }
            return List.copyOf(result.values());
        } catch (ResponseStatusException ex) { throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (Exception ex) { throw unavailable(); }
    }

    public record Plan(String download, String upload) {}
    public Plan findPlan(String id) {
        if (id == null || !id.matches("[1-9][0-9]*")) return new Plan("", "");
        try {
            URI endpoint = URI.create((url.endsWith("/webservice/v1") ? url : url + "/webservice/v1") + "/radgrupos");
            if (!"https".equalsIgnoreCase(endpoint.getScheme()) || endpoint.getHost() == null || endpoint.getUserInfo() != null)
                throw new IllegalArgumentException();
            var body = Map.of("qtype", "radgrupos.id", "query", id, "oper", "=", "page", "1", "rp", "1");
            var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(10))
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8)))
                .header("ixcsoft", "listar").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw unavailable();
            var rows = readPage(response.body()).path("registros");
            if (rows.isEmpty()) return new Plan("", "");
            var row = rows.get(0);
            return new Plan(row.path("download").asText(""), row.path("upload").asText(""));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt(); throw unavailable();
        } catch (Exception ex) { throw unavailable(); }
    }

    private JsonNode readPage(String body) throws java.io.IOException {
        var root = mapper.readTree(body);
        if (root == null || "error".equalsIgnoreCase(root.path("type").asText())
                || root.path("total").asInt(-1) < 0 || root.path("total").asInt() > 100000) throw unavailable();
        if (root.path("total").asInt() == 0 && root.path("registros").isNull()) return root;
        if (root.path("total").asInt() == 0 && !root.has("registros")) return root;
        if (!root.path("registros").isArray()) throw unavailable();
        return root;
    }

    private List<Login> parseRows(JsonNode root, Set<String> allowed) {
        List<Login> logins = new ArrayList<>();
        for (var row : root.path("registros")) {
            String mac;
            try { mac = normalizeMac(row.path("mac").asText()); }
            catch (ResponseStatusException ex) { continue; }
            if (allowed.contains(mac)) logins.add(new Login(row, mac));
        }
        return List.copyOf(logins);
    }

    List<Login> parse(String body, String mac) throws java.io.IOException {
        return parseRows(readPage(body), Set.of(normalizeMac(mac)));
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY,
            "Não foi possível consultar o IXC. Verifique URL, token, permissões e disponibilidade da API.");
    }
}
