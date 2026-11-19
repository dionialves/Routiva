package br.com.scriptping.cisco;

import java.util.*;
import java.util.regex.Pattern;

/** IOS XE output normalized to the existing equipment contract. */
public final class CiscoData {
    private CiscoData() {}
    public static String cliName(String name) {
        if (name == null || !name.matches(CiscoCli.PORT)) throw new IllegalArgumentException("Invalid interface");
        return name;
    }
    private static String canonical(String name) {
        return name.replaceFirst("^Gi(?=[0-9])", "GigabitEthernet")
            .replaceFirst("^Te(?=[0-9])", "TenGigabitEthernet")
            .replaceFirst("^Po(?=[0-9])", "Port-channel")
            .replaceFirst("^Lo(?=[0-9])", "Loopback")
            .replaceFirst("^Vi(?=[0-9])", "Virtual-Access");
    }
    private static Map<String, String> row(String name, String admin, String protocol) {
        var row = new LinkedHashMap<String, String>();
        row.put("name", name); row.put(".id", name);
        row.put("type", name.matches("(?:GigabitEthernet|TenGigabitEthernet)[0-9/]+") ? "ether" : "virtual");
        row.put("disabled", Boolean.toString(admin.contains("admin")));
        row.put("running", Boolean.toString(admin.equals("up") && protocol.equals("up")));
        // Interface family identifies physical maximum; BW is configurable and is not capacity.
        if (row.get("type").equals("ether")) {
            row.put("supported", name.startsWith("Ten") ? "10 Gbps" : "1 Gbps");
            // Use the application's SFP+ representation for Cisco 10G ports, including XFP cards.
            if (name.startsWith("TenGigabitEthernet")) row.put("connector", "sfpplus");
        }
        return row;
    }
    public static List<Map<String, String>> status(String output) {
        var rows = new ArrayList<Map<String, String>>();
        var m = Pattern.compile("(?m)^([A-Za-z][A-Za-z0-9/.-]*)\\s+(admin down|up|down|deleted|reset)[ \t]+(up|down)[ \t]*([^\\r\\n]*)$").matcher(output);
        while (m.find()) {
            String name = canonical(m.group(1));
            if (!name.matches(CiscoCli.PORT)) continue;
            var row = row(name, m.group(2), m.group(3));
            if (!m.group(4).isBlank()) row.put("comment", m.group(4).trim());
            rows.add(row);
        }
        if (rows.isEmpty()) throw new IllegalArgumentException("IOS XE status not recognized");
        return rows;
    }
    public static List<Map<String, String>> interfaces(String output) {
        var rows = new ArrayList<Map<String, String>>();
        var header = Pattern.compile("(?m)^(" + CiscoCli.PORT + ") is (administratively down|up|down|reset|deleted), line protocol is (up|down)[^\\n]*").matcher(output);
        Map<String, String> previous = null;
        int start = 0;
        while (header.find()) {
            if (previous != null) details(previous, output.substring(start, header.start()));
            previous = row(header.group(1), header.group(2), header.group(3));
            rows.add(previous); start = header.end();
        }
        if (previous != null) details(previous, output.substring(start));
        if (rows.isEmpty()) throw new IllegalArgumentException("IOS XE interfaces not recognized");
        return rows;
    }
    private static void extract(Map<String, String> row, String output, String field, String regex) {
        var m = Pattern.compile(regex, Pattern.MULTILINE).matcher(output);
        if (m.find()) row.put(field, m.group(1).trim());
    }
    private static void details(Map<String, String> row, String output) {
        extract(row, output, "rx-byte", "\\b[0-9]+ packets input, ([0-9]+) bytes");
        extract(row, output, "tx-byte", "\\b[0-9]+ packets output, ([0-9]+) bytes");
        extract(row, output, "rx-error", "\\b([0-9]+) input errors");
        extract(row, output, "tx-error", "\\b([0-9]+) output errors");
        extract(row, output, "tx-drop", "Total output drops: ([0-9]+)");
        extract(row, output, "mtu", "MTU ([0-9]+) bytes");
        extract(row, output, "comment", "^\\s*Description: (.+)$");
        extract(row, output, "mac-address", "address is ([a-fA-F0-9.]+)");
        extract(row, output, "rate", "Duplex, ([0-9]+[MGT]bps)");
        if (output.contains("media type is RJ45")) row.put("connector", "electrical");
    }
    public static Map<String, String> overview(String output) {
        var row = new LinkedHashMap<String, String>();
        extract(row, output, "version", "^Cisco IOS XE Software, Version ([^\\s]+)");
        extract(row, output, "board-name", "^cisco (\\S+) ");
        extract(row, output, "uptime", "^\\S+ uptime is (.+)$");
        if (!row.containsKey("version") || !row.containsKey("board-name")) throw new IllegalArgumentException("IOS XE version not recognized");
        return row;
    }
}
