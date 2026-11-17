package br.com.scriptping.ubiquiti;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import java.util.regex.Pattern;

/** Converts airOS 6/8 responses to the shared equipment contract. No absent metric is invented. */
final class UbiquitiData {
    private UbiquitiData() {}
    static Map<String, String> status(String text) {
        var result = new LinkedHashMap<String, String>();
        for (String line : text.replace("\r", "").split("\n")) {
            for (String item : line.split(",(?=[A-Za-z][A-Za-z0-9]*=)")) {
                int equal = item.indexOf('=');
                if (equal > 0) result.put(item.substring(0, equal).trim(), item.substring(equal + 1).trim());
            }
        }
        return result;
    }
    static Map<String, String> overview(Map<String, String> status) {
        var row = new LinkedHashMap<String, String>();
        row.put("manufacturer", "Ubiquiti");
        String[][] fields = {{"identity", "deviceName"}, {"board-name", "platform"}, {"version", "firmwareVersion"},
            {"cpu-load", "cpuUsage"}, {"ssid", "essid"}, {"frequency", "freq"}, {"wireless-mode", "wlanOpmode"}};
        for (var field : fields) copy(row, field[0], status.get(field[1]));
        copy(row, "uptime", seconds(status.get("uptime")));
        for (String kind : List.of("Free", "Total")) {
            String memory = status.get("mem" + kind);
            if (memory != null && memory.matches("\\d+")) row.put(kind.toLowerCase(Locale.ROOT) + "-memory", Long.toString(Long.parseLong(memory) * 1024));
        }
        if (status.containsKey("freq") && status.containsKey("chanbw"))
            row.put("channel", status.get("freq") + " MHz / " + status.get("chanbw") + " MHz");
        return row;
    }
    static List<Map<String, String>> wireless(String text, ObjectMapper json) throws Exception {
        JsonNode nodes = json.readTree(text);
        if (nodes == null || !nodes.isArray()) throw new IllegalArgumentException("Invalid station list");
        var result = new ArrayList<Map<String, String>>();
        for (JsonNode station : nodes) {
            String mac = station.path("mac").asText("");
            if (!mac.matches("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}")) continue;
            var row = new LinkedHashMap<String, String>();
            row.put(".id", mac.toUpperCase(Locale.ROOT) + ":" + station.path("associd").asText(""));
            row.put("mac-address", mac.toUpperCase(Locale.ROOT)); row.put("interface", "ath0");
            row.put("source", "Ubiquiti");
            copy(row, "uptime", seconds(value(station, "uptime")));
            copy(row, "name", value(station, "name"));
            copy(row, "signal", value(station, "signal"));
            Double signal = number(station, "signal"), noise = number(station, "noisefloor");
            if (signal != null && signal < 0 && noise != null && noise < 0 && signal >= noise)
                row.put("signal-to-noise", Double.toString(signal - noise));
            // airOS exports one CCQ; the UI labels it CCQ and never fabricates RX CCQ.
            copy(row, "tx-ccq", value(station, "ccq"));
            for (String direction : List.of("tx", "rx")) {
                Double rate = number(station, direction);
                if (rate != null && rate >= 0) row.put(direction + "-rate", rate + "Mbps");
                copy(row, direction + "-bytes", value(station.path("stats"), direction + "_bytes"));
            }
            String distance = value(station, "distance");
            if (distance != null && distance.matches("\\d+(\\.\\d+)?"))
                row.put("distance", Double.toString(Double.parseDouble(distance) / 1000));
            result.add(row);
        }
        return result;
    }
    static List<Map<String, String>> interfaces(String stats, String config) {
        var rows = new LinkedHashMap<String, Map<String, String>>();
        for (String line : stats.split("\n")) {
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String name = line.substring(0, colon).trim();
            String[] parts = line.substring(colon + 1).trim().split("\\s+");
            if (parts.length < 16) continue;
            var row = new LinkedHashMap<String, String>();
            row.put(".id", name); row.put("name", name);
            row.put("type", name.startsWith("ath") || name.startsWith("wifi") ? "wireless" : name.startsWith("br") ? "bridge" : name.contains(".") ? "vlan" : name.equals("lo") ? "loopback" : "ether");
            row.put("rx-byte", parts[0]); row.put("rx-error", parts[2]); row.put("rx-drop", parts[3]);
            row.put("tx-byte", parts[8]); row.put("tx-error", parts[10]); row.put("tx-drop", parts[11]);
            rows.put(name, row);
        }
        String current = null;
        for (String line : config.split("\n")) {
            if (!line.isBlank() && !Character.isWhitespace(line.charAt(0))) current = line.trim().split("\\s+")[0];
            var row = rows.get(current);
            if (row == null) continue;
            var mac = Pattern.compile("HWaddr\\s+([0-9A-Fa-f:]{17})(?:\\s|$)").matcher(line);
            if (mac.find()) row.put("mac-address", mac.group(1));
            var mtu = Pattern.compile("MTU[: ](\\d+)").matcher(line);
            if (mtu.find()) row.put("mtu", mtu.group(1));
            if (line.contains("MTU")) {
                row.put("disabled", Boolean.toString(!Pattern.compile("\\bUP\\b").matcher(line).find()));
                row.put("running", Boolean.toString(Pattern.compile("\\bRUNNING\\b").matcher(line).find()));
            }
        }
        return new ArrayList<>(rows.values());
    }
    private static void copy(Map<String, String> row, String key, String value) { if (value != null && !value.isBlank()) row.put(key, value); }
    private static String value(JsonNode node, String key) { var value = node.get(key); return value == null || value.isNull() || !value.isValueNode() ? null : value.asText(); }
    private static Double number(JsonNode node, String key) {
        try { double value = Double.parseDouble(value(node, key)); return Double.isFinite(value) ? value : null; }
        catch (Exception ex) { return null; }
    }
    private static String seconds(String value) { return value != null && value.matches("\\d+") ? value + "s" : null; }
}
