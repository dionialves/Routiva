package br.com.scriptping.datacom;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import java.util.regex.Pattern;

/** Converts DmOS operational output into the shared equipment fields. Missing data stays absent. */
public final class DatacomData {
    private DatacomData() {}
    private static final Pattern NAME = Pattern.compile("(" + DatacomCli.PORT.replace(" [0-9]", "[ -][0-9]") + ")", Pattern.CASE_INSENSITIVE);
    public static String cliName(String name) {
        if (name == null) throw new IllegalArgumentException("Missing interface");
        String cli = name.replaceFirst("-(?=[0-9]+/)", " ");
        if (!DatacomCli.allowed("show interface " + cli)) throw new IllegalArgumentException("Invalid interface");
        return cli;
    }
    /** DM4250 24XS+2QX physical cages, verified against the manufacturer's datasheet.
     * Do not infer these from negotiated speed or apply the profile to other cards/chassis. */
    public static void applyHardware(List<Map<String, String>> rows, String platform) {
        if (platform == null) return;
        boolean chassis = Pattern.compile("(?im)^\\s*1\\s+DM4250\\s+").matcher(platform).find();
        boolean card = Pattern.compile("(?im)^\\s*1/1\\s+24XS\\+2QX\\s+").matcher(platform).find();
        if (!chassis || !card) return;
        for (var row : rows) {
            var port = Pattern.compile("^(ten|forty)-gigabit-ethernet-1/1/([0-9]{1,3})$").matcher(row.getOrDefault("name", ""));
            if (!port.matches()) continue;
            int number = Integer.parseInt(port.group(2));
            boolean ten = port.group(1).equals("ten");
            if (number < 1 || number > (ten ? 24 : 2)) continue;
            row.put("connector", ten ? "sfpplus" : "qsfpplus");
            row.put("supported", ten ? "10 Gbps" : "40 Gbps");
        }
    }
    public static Map<String, String> overview(String platform, String firmware, String cpu, String memory, String uptime) {
        var row = new LinkedHashMap<String, String>();
        var model = Pattern.compile("\\bDM[0-9]{4}[A-Za-z0-9-]*\\b", Pattern.CASE_INSENSITIVE).matcher(platform);
        if (!model.find()) throw new IllegalArgumentException("DmOS platform not recognized");
        row.put("board-name", model.group());
        var version = Pattern.compile("(?im)(?:DmOS\\s*|(?:firmware(?: version)?|version)\\s*[:=]?\\s*)([0-9]+\\.[0-9]+\\.[0-9]+[\\w.-]*)").matcher(firmware + "\n" + platform);
        if (version.find()) row.put("version", version.group(1));
        else {
            var ready = Pattern.compile("(?im)\\b(?:Master|Ready)\\b[^\\n]*?\\b([0-9]+\\.[0-9]+\\.[0-9]+[\\w.-]*)").matcher(platform);
            if (ready.find()) row.put("version", ready.group(1));
        }
        var active = Pattern.compile("(?im)^\\s*active\\s*[:|]?\\s*([0-9.]+)%").matcher(cpu);
        if (active.find()) row.put("cpu-load", active.group(1));
        for (String field : List.of("total", "free")) {
            var m = Pattern.compile("(?im)^\\s*" + field + "\\s*[:|]?\\s*([0-9.]+)\\s*([KMGT]i?B|[KMGT]B|bytes)?\\b", Pattern.CASE_INSENSITIVE).matcher(memory);
            if (m.find() && m.group(2) != null) {
                String unit = m.group(2).toUpperCase(Locale.ROOT);
                int power = "KMGT".indexOf(unit.charAt(0)) + 1;
                try { row.put(field + "-memory", new BigDecimal(m.group(1)).multiply(BigDecimal.valueOf(1024).pow(Math.max(0, power))).toBigInteger().toString()); }
                catch (NumberFormatException ignored) { }
            }
        }
        var up = Pattern.compile("(?i)\\bup\\s+(.+?)(?:,\\s*load average|$)").matcher(uptime.strip());
        if (up.find()) row.put("uptime", up.group(1).trim());
        return row;
    }
    public static List<Map<String, String>> links(String output) {
        var rows = new LinkedHashMap<String, Map<String, String>>();
        String[] headers = null;
        String interfaceType = null;
        for (String line : output.split("\\R")) {
            var section = Pattern.compile("^\\s*([A-Za-z -]+) Interfaces:\\s*$", Pattern.CASE_INSENSITIVE).matcher(line);
            if (section.matches()) {
                String type = section.group(1).trim().toLowerCase(Locale.ROOT).replaceAll("[ -]+", "-");
                interfaceType = DatacomCli.allowed("show interface " + type + " 1/1/1") ? type : null;
                headers = null;
                continue;
            }
            // DmOS groups port numbers under their Ethernet family, with a multi-line header.
            // Ten and Forty Gigabit may both contain 1/1/1: the family is part of the identity.
            if (interfaceType != null && line.strip().matches("[0-9]{1,3}/[0-9]{1,3}/[0-9]{1,3}\\s+.*")) {
                String[] values = line.strip().split("\\s+", 9);
                if (values.length >= 8 && Set.of("up", "down").contains(values[1].toLowerCase(Locale.ROOT))
                        && Set.of("true", "false").contains(values[2].toLowerCase(Locale.ROOT))) {
                    String name = interfaceType + "-" + values[0];
                    var row = new LinkedHashMap<String, String>();
                    row.put(".id", name); row.put("name", name);
                    row.put("type", interfaceType.equals("gpon") || interfaceType.equals("xgs-pon") ? "gpon" : "ether");
                    field(row, "link", values[1]); field(row, "shutdown", values[2]); field(row, "speed", values[3]);
                    row.put("duplex", values[4]); row.put("disabled-by", values[5]);
                    row.put("blocked-by", values[6]); row.put("parent-lag", values[7]);
                    if (values.length == 9 && !values[8].equals("-")) row.put("comment", values[8]);
                    rows.put(name, row);
                }
                continue;
            }
            if (line.toLowerCase(Locale.ROOT).contains("interface") && !NAME.matcher(line).find()) {
                String[] cells = cells(line);
                if (cells.length > 1) headers = cells;
            }
            var match = NAME.matcher(line);
            if (!match.find()) continue;
            String name = match.group().toLowerCase(Locale.ROOT).replaceFirst(" (?=[0-9])", "-");
            var row = new LinkedHashMap<String, String>();
            row.put(".id", name); row.put("name", name);
            row.put("type", name.startsWith("gpon-") || name.startsWith("xgs-pon-") ? "gpon" : "ether");
            String tail = line.substring(match.end()).trim();
            String[] values = cells(tail.replaceFirst("^\\|", "").trim());
            // Only header-labelled status is accepted; never guess up/down from an unrelated field.
            if (headers != null && headers.length == values.length + 1) {
                for (int i = 0; i < values.length; i++) field(row, headers[i + 1], values[i]);
            }
            rows.put(name, row);
        }
        if (rows.isEmpty()) throw new IllegalArgumentException("DmOS interface output not recognized");
        if (rows.size() > 256) throw new IllegalArgumentException("Too many interfaces");
        return new ArrayList<>(rows.values());
    }
    private static String[] cells(String line) { return line.strip().split(line.contains("|") ? "\\s*\\|\\s*" : "\\s{2,}"); }
    public static void details(Map<String, String> row, String output) {
        for (String line : output.split("\\R")) {
            var m = Pattern.compile("^\\s*([A-Za-z][A-Za-z /()-]+?)\\s*:\\s*(.*?)\\s*$").matcher(line);
            if (m.matches()) field(row, m.group(1), m.group(2));
            else {
                var leaf = Pattern.compile("^\\s*([a-z][a-z-]*)\\s+(.+?)\\s*;?$").matcher(line);
                if (leaf.matches()) field(row, leaf.group(1), leaf.group(2));
            }
        }
    }
    private static void field(Map<String, String> row, String label, String value) {
        String key = label.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        String target = switch (key) {
            case "description" -> "comment";
            case "supportedspeeds", "supportedrates", "supported" -> "supported";
            case "mtu" -> "mtu";
            case "mac", "macaddress", "hardwareaddress" -> "mac-address";
            case "inoctets" -> "rx-byte";
            case "outoctets" -> "tx-byte";
            case "inerrors" -> "rx-error";
            case "outerrors" -> "tx-error";
            case "indiscards" -> "rx-drop";
            case "outdiscards" -> "tx-drop";
            default -> null;
        };
        if (target != null) {
            if (!target.matches("(?:rx|tx)-.*") || unsigned(value) != null) row.put(target, value);
            return;
        }
        String state = value.toLowerCase(Locale.ROOT).trim();
        if (key.equals("shutdown") && Set.of("true", "false").contains(state)) row.put("disabled", state);
        if (Set.of("connector", "connectortype", "physicaltype", "porttype").contains(key)) {
            String connector = switch (state.replace("-", "").replace(" ", "")) {
                case "rj45", "electrical" -> "electrical";
                case "sfp" -> "sfp";
                case "sfp+", "sfpplus" -> "sfpplus";
                case "sfp28" -> "sfp28";
                case "qsfp+", "qsfpplus" -> "qsfpplus";
                case "sfp56" -> "sfp56";
                case "qsfp28" -> "qsfp28";
                case "qsfp56" -> "qsfp56";
                case "qsfpdd" -> "qsfpdd";
                default -> null;
            };
            if (connector != null) row.put("connector", connector);
        }
        if (Set.of("admin", "portadmin", "adminstate", "adminstatus", "administrativestate", "administrativestatus").contains(key)) {
            if (Set.of("up", "enabled", "down", "disabled").contains(state)) row.put("disabled", Boolean.toString(state.equals("down") || state.equals("disabled")));
        } else if (Set.of("link", "linkstate", "linkstatus", "oper", "operstatus", "operationalstate", "operationalstatus").contains(key)) {
            if (Set.of("up", "down").contains(state)) row.put("running", Boolean.toString(state.equals("up")));
        } else if (Set.of("speed", "linkspeed", "operationalspeed").contains(key)) {
            var rate = Pattern.compile("([0-9.]+)\\s*([KMGT])(?:b(?:it)?(?:/s|ps)?)?", Pattern.CASE_INSENSITIVE).matcher(value);
            if (rate.matches()) row.put("rate", rate.group(1) + rate.group(2).toUpperCase(Locale.ROOT) + "bps");
        }
    }
    static BigInteger unsigned(String value) {
        try { var n = new BigInteger(value); return n.signum() >= 0 && n.bitLength() <= 64 ? n : null; }
        catch (RuntimeException ex) { return null; }
    }
    public static String rate(String previous, String current, long elapsed) {
        var a = unsigned(previous); var b = unsigned(current);
        if (a == null || b == null || elapsed <= 0 || elapsed > 30000 || b.compareTo(a) < 0) return null;
        return b.subtract(a).multiply(BigInteger.valueOf(8000)).divide(BigInteger.valueOf(elapsed)).toString();
    }
}
