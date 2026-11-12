package br.com.scriptping.mikrotik;

import java.util.*;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Session-only rolling windows. Missing metrics never enter a window. */
@Component
public class WirelessQuality {
    private static final Pattern NUMBER = Pattern.compile("^\\s*(-?\\d+(?:\\.\\d+)?)");
    private static final String[] METRICS = {"signal", "signal-to-noise", "tx-ccq", "rx-ccq", "tx-rate", "rx-rate"};
    private static final String[] LABELS = {"Sinal médio", "SNR médio", "TX CCQ médio", "RX CCQ médio"};
    private static final String[] LEVELS = {"Normal", "Atenção", "Ruim", "Crítico"};
    private final Map<String, Station> stations = new LinkedHashMap<>();
    private final double[][] limits;
    private final int window;
    private final long retention;

    public WirelessQuality(@Value("${wireless.quality.window:30}") int window,
            @Value("${wireless.quality.retention-ms:120000}") long retention,
            @Value("${wireless.quality.signal:-65,-75,-83}") double[] signal,
            @Value("${wireless.quality.snr:30,25,15}") double[] snr,
            @Value("${wireless.quality.ccq:90,80,60}") double[] ccq) {
        if (window < 1 || retention < 1) throw new IllegalArgumentException("Invalid wireless window/retention");
        for (var thresholds : List.of(signal, snr, ccq)) {
            if (thresholds.length != 3 || !Double.isFinite(thresholds[0]) || !Double.isFinite(thresholds[1])
                    || !Double.isFinite(thresholds[2]) || thresholds[0] < thresholds[1] || thresholds[1] < thresholds[2])
                throw new IllegalArgumentException("Wireless thresholds must contain three descending values");
        }
        this.window = window; this.retention = retention;
        limits = new double[][] {signal, snr, ccq, ccq};
    }

    private static class Station {
        final List<ArrayDeque<Double>> samples = new ArrayList<>();
        final List<ArrayDeque<Double>> activeCcq = List.of(new ArrayDeque<>(), new ArrayDeque<>());
        Map<String, String> last;
        long seen;
        boolean connected;
        String uptime;
        Station() { for (int i = 0; i < METRICS.length; i++) samples.add(new ArrayDeque<>()); }
    }

    static Double numeric(String raw) {
        if (raw == null) return null;
        var match = NUMBER.matcher(raw);
        if (!match.find()) return null;
        double value = Double.parseDouble(match.group(1));
        return Double.isFinite(value) ? value : null;
    }

    private static boolean trafficActive(Map<String, String> raw, Map<String, String> previous, boolean transmit) {
        String direction = transmit ? "tx" : "rx";
        Double direct = numeric(raw.get(direction + "-bits-per-second"));
        if (direct != null) return direct > 0;
        if (previous == null) return false;
        return counter(raw, direction, transmit) > counter(previous, direction, transmit)
            && counter(previous, direction, transmit) >= 0;
    }

    private static long counter(Map<String, String> row, String direction, boolean transmit) {
        String[] pair = row.getOrDefault("bytes", "").split(",");
        int index = transmit ? 0 : 1;
        String raw = row.getOrDefault(direction + "-bytes", index < pair.length ? pair[index] : "");
        try { return Long.parseLong(raw.trim()); }
        catch (NumberFormatException ex) { return -1; }
    }

    private int level(int metric, double value) {
        var t = limits[metric];
        return value >= t[0] ? 0 : value >= t[1] ? 1 : (metric == 0 ? value > t[2] : value >= t[2]) ? 2 : 3;
    }

    private static Double uptime(String raw) {
        if (raw == null) return null;
        var matcher = Pattern.compile("(\\d+(?:\\.\\d+)?)(ms|w|d|h|m|s)").matcher(raw);
        double seconds = 0; boolean found = false;
        while (matcher.find()) {
            found = true;
            seconds += Double.parseDouble(matcher.group(1)) * switch (matcher.group(2)) {
                case "w" -> 604800; case "d" -> 86400; case "h" -> 3600;
                case "m" -> 60; case "ms" -> .001; default -> 1;
            };
        }
        return found ? seconds : null;
    }

    public synchronized List<Map<String, String>> update(String ap, List<Map<String, String>> rows, long now) {
        stations.entrySet().removeIf(e -> now - e.getValue().seen > retention);
        Set<String> present = new HashSet<>();
        List<Map<String, String>> result = new ArrayList<>();
        for (var raw : rows) {
            if (raw.get("mac-address") == null || raw.get("interface") == null) continue;
            String key = ap + "\u0000" + raw.get("interface") + "\u0000" + raw.get("mac-address").toUpperCase(Locale.ROOT);
            if (!present.add(key)) continue;
            Station s = stations.computeIfAbsent(key, ignored -> new Station());
            Double previous = uptime(s.uptime), current = uptime(raw.get("uptime"));
            if (!s.connected || (previous != null && current != null && current < previous)
                    || !Objects.equals(raw.get(".id"), s.last == null ? null : s.last.get(".id"))) {
                s.samples.forEach(Deque::clear);
                s.activeCcq.forEach(Deque::clear);
                s.last = null;
            }
            var row = new LinkedHashMap<>(raw);
            int worst = -1, count = Integer.MAX_VALUE;
            List<String> reasons = new ArrayList<>(), alerts = new ArrayList<>();
            for (int i = 0; i < METRICS.length; i++) {
                String name = METRICS[i];
                Double value = numeric(raw.get(i == 0 && !raw.containsKey("signal") ? "signal-strength" : name));
                if (value != null && ((i == 0 && (value >= 0 || value < -150))
                        || (i > 0 && value < 0) || ((i == 2 || i == 3) && value > 100))) value = null;
                var samples = s.samples.get(i);
                boolean ccq = i == 2 || i == 3;
                boolean active = !ccq || trafficActive(raw, s.last, i == 2);
                if (value != null) {
                    if (i >= 4) {
                        String rate = raw.get(name).toLowerCase(Locale.ROOT);
                        if (rate.contains("gbps")) value *= 1000;
                        else if (rate.contains("kbps")) value /= 1000;
                        else if (!rate.contains("mbps")) value /= 1_000_000;
                    }
                    if (ccq && active) {
                        var evidence = s.activeCcq.get(i - 2);
                        evidence.addLast(value);
                        if (evidence.size() > window) evidence.removeFirst();
                    }
                    samples.addLast(value);
                    if (samples.size() > window) samples.removeFirst();
                }
                if (ccq && !active && value != null) {
                    reasons.add(LABELS[i] + ": sem tráfego confirmado nesta leitura.");
                }
                if (samples.isEmpty()) continue;
                double mean = samples.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
                row.put("avg-" + name, String.format(Locale.ROOT, "%.2f", mean));
                if (i < 4 && value != null) {
                    int severity = level(i, mean);
                    row.put(name + "-severity", String.valueOf(severity));
                    if (ccq) {
                        if (!active) continue;
                        var evidence = s.activeCcq.get(i - 2);
                        double activeMean = evidence.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
                        severity = level(i, activeMean);
                        if (severity > 0 && evidence.size() < Math.min(2, window)) {
                            reasons.add(LABELS[i] + ": aguardando confirmação com tráfego.");
                            continue;
                        }
                        reasons.add(LABELS[i] + " com tráfego: " + String.format(Locale.ROOT, "%.2f%%", activeMean) + " — " + LEVELS[severity]);
                    }
                    worst = Math.max(worst, severity); count = Math.min(count, samples.size());
                    if (!ccq) reasons.add(LABELS[i] + ": " + row.get("avg-" + name) + (i == 0 ? " dBm" : i == 1 ? " dB" : "%") + " — " + LEVELS[severity]);
                    if (level(i, value) == 3) alerts.add(LABELS[i].replace(" médio", "") + " crítico agora: " + value);
                } else if (i < 4) {
                    row.remove("avg-" + name); // Current association does not report this metric.
                }
            }
            row.put("quality", worst < 0 ? "Indisponível" : LEVELS[worst]);
            row.put("quality-severity", String.valueOf(worst));
            row.put("quality-reason", "Qualidade: " + row.get("quality") + "\n" + String.join("\n", reasons)
                + (worst < 0 ? "\nSem indicadores válidos." : "\nMotivo: pior condição entre os indicadores disponíveis.")
                + (count < window ? "\nProvisório: janela em formação." : "")
                + (numeric(raw.getOrDefault("signal", raw.get("signal-strength"))) != null
                    && numeric(raw.getOrDefault("signal", raw.get("signal-strength"))) > -50 ? "\nSinal forte: avaliar junto com SNR e CCQ." : ""));
            row.put("critical-alert", String.join("; ", alerts));
            row.put("connection-status", "connected");
            s.last = row; s.seen = now; s.connected = true; s.uptime = raw.get("uptime");
            result.add(row);
        }
        for (var entry : stations.entrySet()) {
            if (!entry.getKey().startsWith(ap + "\u0000") || present.contains(entry.getKey())) continue;
            var s = entry.getValue(); s.connected = false;
            var row = new LinkedHashMap<>(s.last);
            row.put("connection-status", "disconnected"); row.put("critical-alert", "");
            result.add(row);
        }
        return result;
    }
}
