package br.com.scriptping.mimosa;

import br.com.scriptping.equipment.EquipmentProvider;
import br.com.scriptping.hosts.HostConnections;
import br.com.scriptping.zabbix.Host;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Firmware 2.10 dashboard adapter. Never exports the configuration (which contains secrets). */
@Component
public class MimosaEquipmentProvider implements EquipmentProvider {
    private final HostConnections settings;
    private final ObjectMapper json;
    private final MimosaHttps https;
    private final Semaphore connections = new Semaphore(8);
    public MimosaEquipmentProvider(HostConnections settings, ObjectMapper json, MimosaHttps https) {
        this.settings = settings; this.json = json; this.https = https;
    }
    public String manufacturer() { return "Mimosa"; }
    public Set<String> capabilities() { return Set.of("overview", "discover", "interfaces", "connection"); }
    public Snapshot read(Host host, String section, String selectedInterface) {
        return query(host, section, selectedInterface, settings.require(host));
    }
    public void testConnection(Host host, Connection connection) { query(host, "overview", null, connection); }
    public Snapshot query(Host host, String section, String selected, Connection credentials) {
        if (!capabilities().contains(section)) throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED);
        if (!connections.tryAcquire()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Consultas ocupadas. Tente novamente.");
        try {
            byte[] bytes = https.dashboard(host.ip(), credentials.port(), credentials.password(), "connection".equals(section) && ("Wireless".equals(selected) || "wireless".equals(selected)));
            var result = snapshot(json.readTree(bytes), section, selected, System.currentTimeMillis());
            return result;
        } catch (ResponseStatusException ex) { throw ex;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "Não foi possível consultar o equipamento. Confira HTTPS, porta e senha. Se o certificado mudou, revise a confiança do equipamento.");
        } finally { connections.release(); }
    }

    static Snapshot snapshot(JsonNode root, String section, String selected, long at) {
        var realtime = root == null ? null : root.get("realtime");
        if (realtime == null || !realtime.isObject()) throw new IllegalArgumentException("Invalid dashboard");
        var device = realtime.path("device_info");
        if (device.path("Version").asText("").isBlank() || device.path("Model").asText("").isBlank())
            throw new IllegalArgumentException("Missing device identity");
        if (section.equals("overview")) {
            var row = new LinkedHashMap<String, String>();
            row.put("manufacturer", "Mimosa");
            copy(row, "identity", device, "DeviceName"); copy(row, "board-name", device, "Model");
            copy(row, "version", device, "Version");
            copy(row, "ssid", realtime.path("connection"), "SSID");
            copy(row, "frequency", realtime.path("signalmeter"), "Chains_1_2");
            return new Snapshot(at, List.of(row), "");
        }
        var rows = new ArrayList<Map<String, String>>();
        if (!device.path("MacEther").asText("").isBlank()) {
            var row = new LinkedHashMap<String, String>();
            row.put(".id", "ethernet"); row.put("name", "Ethernet (RJ45)"); row.put("type", "ether"); row.put("connector", "electrical");
            copy(row, "mac-address", device, "MacEther");
            String advertised = root.path("config").path("Ethernet").path("AdvertiseOptions").asText("");
            var modes = java.util.regex.Pattern.compile("(?i)(10|100|1000)BaseT").matcher(advertised);
            var rates = new LinkedHashSet<String>();
            while (modes.find()) rates.add(modes.group(1) + "Mbps");
            if (!rates.isEmpty()) row.put("advertising", String.join(",", rates));
            String speed = device.path("ethernetSpeed").asText("").trim();
            if (speed.equalsIgnoreCase("Link Down")) row.put("running", "false");
            else {
                var link = java.util.regex.Pattern.compile("(?i)\\b(10|100|1000)\\s*(?:base|mbps|m)").matcher(speed);
                if (link.find()) { row.put("running", "true"); row.put("rate", link.group(1) + "Mbps"); }
            }
            // B5c 2.10 UI: Ethernet statistics, RX Octets OK / TX Octets OK.
            var stats = realtime.path("wan_stats");
            for (var field : List.of(new String[]{"rx-byte", "Rx#05"}, new String[]{"tx-byte", "Tx#02"},
                    new String[]{"rx-error", "Rx#04"}, new String[]{"tx-error", "Tx#03"})) {
                String value = stats.path(field[1]).asText("");
                if (unsigned(value) != null) row.put(field[0], value);
            }
            copy(row, "counter-generation", device, "LastReboot");
            rows.add(row);
        }
        var connection = realtime.path("connection");
        if (!device.path("Mac5_0").asText("").isBlank() || connection.has("connected")) {
            var row = new LinkedHashMap<String, String>();
            row.put(".id", "wireless"); row.put("name", "Wireless"); row.put("type", "wireless"); row.put("connector", "wireless");
            copy(row, "mac-address", device, "Mac5_0");
            String connected = connection.path("connected").asText("");
            if (connected.equals("1") || connected.equals("0")) row.put("running", Boolean.toString(connected.equals("1")));
            copy(row, "comment", connection, "SSID");
            wirelessRates(row, root.path("wirelessTraffic").path("tput1min").asText(""), at);
            rows.add(row);
        }
        if (section.equals("connection")) {
            rows.removeIf(row -> !Objects.equals(selected, row.get(".id")) && !Objects.equals(selected, row.get("name")));
            if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Selecione uma interface válida.");
        }
        boolean partial = rows.isEmpty() || rows.stream().anyMatch(row -> !"wireless".equals(row.get("type")) && (!row.containsKey("rx-byte") || !row.containsKey("tx-byte")));
        return new Snapshot(at, rows, partial ? "Contadores indisponíveis nesta leitura." : "");
    }
    static void wirelessRates(Map<String, String> row, String series, long at) {
        // B5c updateTput: first value TX, second RX, bytes/s multiplied by eight.
        String[] points = series.split(";");
        if (points.length == 0) return;
        try {
            String[] point = points[points.length - 1].split(":");
            long sampledAt = Math.multiplyExact(Long.parseLong(point[0]), 1000);
            if (at - sampledAt > 30000 || sampledAt - at > 5000) return;
            String[] rates = point[1].split(",");
            var tx = new java.math.BigDecimal(rates[0]); var rx = new java.math.BigDecimal(rates[1]);
            if (tx.signum() < 0 || rx.signum() < 0
                    || tx.compareTo(new java.math.BigDecimal("1000000000000")) > 0
                    || rx.compareTo(new java.math.BigDecimal("1000000000000")) > 0) return;
            row.put("tx-bits-per-second", tx.multiply(java.math.BigDecimal.valueOf(8)).toBigInteger().toString());
            row.put("rx-bits-per-second", rx.multiply(java.math.BigDecimal.valueOf(8)).toBigInteger().toString());
        } catch (RuntimeException invalid) { /* Missing/stale/invalid is never zero. */ }
    }
    private static java.math.BigInteger unsigned(String value) {
        try {
            var number = new java.math.BigInteger(value);
            return number.signum() >= 0 && number.bitLength() <= 64 ? number : null;
        } catch (RuntimeException ex) { return null; }
    }
    private static void copy(Map<String, String> row, String key, JsonNode source, String field) {
        String value = source.path(field).asText("");
        if (!value.isBlank()) row.put(key, value);
    }
}
