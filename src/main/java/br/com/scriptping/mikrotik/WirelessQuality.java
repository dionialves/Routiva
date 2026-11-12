package br.com.scriptping.mikrotik;

import java.math.BigInteger;
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
    private final long retention, historyMs;
    private final double idleBps, significantBps, strongSignal;
    private final double[] hysteresis;
    private final int confirmations;

    public WirelessQuality(@Value("${wireless.quality.window:30}") int window,
            @Value("${wireless.quality.retention-ms:120000}") long retention,
            @Value("${wireless.quality.signal:-71,-75,-83}") double[] signal,
            @Value("${wireless.quality.snr:30,25,15}") double[] snr,
            @Value("${wireless.quality.ccq:90,80,60}") double[] ccq,
            @Value("${wireless.quality.idle-bps:100000}") double idleBps,
            @Value("${wireless.quality.significant-bps:1000000}") double significantBps,
            @Value("${wireless.quality.history-ms:60000}") long historyMs,
            @Value("${wireless.quality.confirmations:2}") int confirmations,
            @Value("${wireless.quality.strong-signal:-50}") double strongSignal,
            @Value("${wireless.quality.hysteresis:1,1,2}") double[] hysteresis) {
        if (!(idleBps > 0 && significantBps > idleBps && Double.isFinite(significantBps))
                || historyMs < 1 || confirmations < 1 || !Double.isFinite(strongSignal)
                || hysteresis.length != 3 || Arrays.stream(hysteresis).anyMatch(h -> !Double.isFinite(h) || h < 0))
            throw new IllegalArgumentException("Invalid wireless quality calibration");
        this.idleBps = idleBps;
        this.significantBps = significantBps;
        this.historyMs = historyMs;
        this.confirmations = confirmations;
        this.strongSignal = strongSignal;
        this.hysteresis = hysteresis;
        if (window < 1 || retention < 1) throw new IllegalArgumentException("Invalid wireless window/retention");
        for (var thresholds : List.of(signal, snr, ccq)) {
            if (thresholds.length != 3 || !Double.isFinite(thresholds[0]) || !Double.isFinite(thresholds[1])
                    || !Double.isFinite(thresholds[2]) || thresholds[0] < thresholds[1] || thresholds[1] < thresholds[2])
                throw new IllegalArgumentException("Wireless thresholds must contain three descending values");
        }
        this.window = window; this.retention = retention;
        limits = new double[][] {signal, snr, ccq, ccq};
    }

    private record Sample(long at, double value) {}

    private static class Window {
        final ArrayDeque<Sample> samples = new ArrayDeque<>();

        double sum;

        Double update(long now, long duration, Double value) {
            while (!samples.isEmpty() && samples.getFirst().at <= now - duration) sum -= samples.removeFirst().value;
            if (samples.isEmpty()) sum = 0;
            if (value == null) return null;
            samples.addLast(new Sample(now, value));
            sum += value;
            return sum / samples.size();
        }
    }

    private static class Direction {
        final Window active = new Window();
        final int[] streak = new int[4];
        int severity = -1;
        Double reference;
        long referenceAt;

        void interrupt() {
            Arrays.fill(streak, 0);
            severity = -1;
        }
    }

    private static class Station {
        final Window[] samples = new Window[METRICS.length];
        final Direction[] ccq = {new Direction(), new Direction()};
        final int[] radioSeverity = {-1, -1};
        Map<String, String> last;
        long seen, started;
        boolean connected;
        Station(long now) {
            Arrays.setAll(samples, i -> new Window());
            started = now;
        }
    }

    static Double numeric(String raw) {
        if (raw == null) return null;
        var match = NUMBER.matcher(raw);
        if (!match.find()) return null;
        double value = Double.parseDouble(match.group(1));
        return Double.isFinite(value) ? value : null;
    }

    private static Double traffic(Map<String, String> raw, Station station, String direction, long now) {
        String direct = raw.get(direction + "-bits-per-second");
        if (direct != null && direct.matches("[0-9]+")) {
            Double rate = numeric(direct);
            if (rate != null) return rate;
        }
        long elapsed = now - station.seen;
        if (station.last == null || elapsed <= 0 || elapsed > 15000) return null;
        var current = counter(raw, direction);
        var previous = counter(station.last, direction);
        if (current == null || previous == null || current.compareTo(previous) < 0) return null;
        return current.subtract(previous).doubleValue() * 8000 / elapsed;
    }

    private static BigInteger counter(Map<String, String> row, String direction) {
        String[] pair = row.getOrDefault("bytes", "").split(",");
        int index = direction.equals("tx") ? 0 : 1;
        String raw = row.getOrDefault(direction + "-bytes", index < pair.length ? pair[index] : "").trim();
        return raw.matches("[0-9]{1,20}") ? new BigInteger(raw) : null;
    }

    private int level(int metric, double value) {
        var t = limits[metric];
        return (metric == 0 ? value > t[0] : value >= t[0]) ? 0 : value >= t[1] ? 1 : (metric == 0 ? value > t[2] : value >= t[2]) ? 2 : 3;
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

    private int stableLevel(int metric, double mean, int previous) {
        int candidate = level(metric, mean);
        if (previous <= candidate) return candidate;
        // Recovery needs extra margin. A worsening measurement uses the published thresholds.
        return Math.min(previous, level(metric, mean - hysteresis[Math.min(metric, 2)]));
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private int assessCcq(int metric, Station station, Map<String, String> row, long now,
            Double value, Double mean, List<String> reasons, List<String> alerts) {
        String name = METRICS[metric];
        String direction = metric == 2 ? "tx" : "rx";
        var state = station.ccq[metric - 2];
        Double bps = traffic(row, station, direction, now);
        boolean significant = bps != null && bps >= significantBps;
        Double active = state.active.update(now, window * 1000L, significant ? value : null);
        String label = direction.toUpperCase(Locale.ROOT);
        if (mean != null) reasons.add(label + " CCQ médio geral: " + format(mean) + "%");
        String activity = bps == null ? "sem medição" : significant ? "atividade significativa"
                : bps < idleBps ? "ocioso" : "atividade baixa";
        if (bps != null) row.put(direction + "-traffic-bps", Double.toString(bps));
        reasons.add("Tráfego " + label + " atual: " + (bps == null ? activity : format(bps / 1000) + " kbps (" + activity + ")"));
        if (active == null) {
            state.interrupt();
            String reason = value == null || bps == null ? "sem dados suficientes" : "por baixa atividade";
            reasons.add(label + " CCQ não avaliado " + reason + (activity.equals("atividade baixa") ? " (baixa representatividade)." : "."));
            if (state.reference != null && now - state.referenceAt < historyMs) {
                reasons.add("Última avaliação válida " + label + ": " + format(state.reference) + "% (há "
                        + (now - state.referenceAt) / 1000 + " s).");
            }
            return -1;
        }

        row.put("active-" + name, format(active));
        int instant = level(metric, value);
        if (instant == 3) alerts.add(label + " CCQ crítico agora: " + format(value) + "%");
        int candidate = stableLevel(metric, active, state.severity);
        int confirmed = 0;
        for (int threshold = 1; threshold <= 3; threshold++) {
            state.streak[threshold] = instant >= threshold
                    ? Math.min(confirmations, state.streak[threshold] + 1) : 0;
            if (state.streak[threshold] >= confirmations) confirmed = threshold;
        }
        if (candidate <= state.severity || candidate == 0) state.severity = candidate;
        else if (confirmed > state.severity && confirmed > 0) state.severity = Math.min(candidate, confirmed);
        String assessment = state.severity < 0 ? "não avaliado" : LEVELS[state.severity];
        reasons.add(label + " CCQ sob tráfego: " + format(active) + "% — " + assessment
                + (candidate > state.severity ? " (provisório, aguardando confirmação)." : ""));
        if (state.severity >= 0) {
            row.put(name + "-severity", String.valueOf(state.severity));
            if (candidate == state.severity) {
                state.reference = active;
                state.referenceAt = now;
            }
        }
        return state.severity;
    }

    public synchronized List<Map<String, String>> update(String ap, List<Map<String, String>> rows, long now) {
        stations.entrySet().removeIf(e -> now - e.getValue().seen > retention);
        Set<String> present = new HashSet<>();
        List<Map<String, String>> result = new ArrayList<>();
        for (var raw : rows) {
            if (raw.get("mac-address") == null || raw.get("interface") == null) continue;
            String key = ap + "\u0000" + raw.get("interface") + "\u0000" + raw.get("mac-address").toUpperCase(Locale.ROOT);
            if (!present.add(key)) continue;
            Station s = stations.get(key);
            if (s != null && s.connected && now <= s.seen) {
                result.add(s.last);
                continue;
            }
            Double previous = s == null ? null : uptime(s.last.get("uptime"));
            Double current = uptime(raw.get("uptime"));
            if (s == null || !s.connected || (previous != null && current != null && current < previous)
                    || !Objects.equals(raw.get(".id"), s.last.get(".id"))) {
                s = new Station(now);
                stations.put(key, s);
            } else if (now - s.seen > 3000) {
                for (var direction : s.ccq) direction.interrupt();
                if (now - s.seen >= window * 1000L) {
                    Arrays.fill(s.radioSeverity, -1);
                    s.started = now;
                }
            }
            var row = new LinkedHashMap<>(raw);
            int worst = -1;
            List<String> reasons = new ArrayList<>(), alerts = new ArrayList<>();
            for (int i = 0; i < METRICS.length; i++) {
                String name = METRICS[i];
                Double value = numeric(raw.get(i == 0 && !raw.containsKey("signal") ? "signal-strength" : name));
                if (value != null && ((i == 0 && (value >= 0 || value < -150))
                        || (i >= 2 && value < 0) || ((i == 2 || i == 3) && value > 100))) value = null;
                if (i >= 4 && value != null) {
                    String rate = raw.get(name).toLowerCase(Locale.ROOT);
                    if (rate.contains("gbps")) value *= 1000;
                    else if (rate.contains("kbps")) value /= 1000;
                    else if (!rate.contains("mbps")) value /= 1_000_000;
                }
                var samples = s.samples[i];
                Double mean = samples.update(now, window * 1000L, value);
                if (mean != null) row.put("avg-" + name, format(mean));
                if (i == 2 || i == 3) {
                    worst = Math.max(worst, assessCcq(i, s, row, now, value, mean, reasons, alerts));
                } else if (i < 2) {
                    if (mean == null) {
                        s.radioSeverity[i] = -1;
                        continue;
                    }
                    int severity = stableLevel(i, mean, s.radioSeverity[i]);
                    s.radioSeverity[i] = severity;
                    worst = Math.max(worst, severity);
                    row.put(name + "-severity", String.valueOf(severity));
                    reasons.add(LABELS[i] + ": " + format(mean) + (i == 0 ? " dBm" : " dB") + " — " + LEVELS[severity]);
                    if (level(i, value) == 3) alerts.add(LABELS[i].replace(" médio", "") + " crítico agora: " + value);
                    if (i == 0 && value > strongSignal) reasons.add("Sinal forte: verificar possível excesso de potência.");
                }
            }
            row.put("quality", worst < 0 ? "Indisponível" : LEVELS[worst]);
            row.put("quality-severity", String.valueOf(worst));
            // Presentation summary uses the winning assessment, never instantaneous CCQ.
            String primary = "Dados insuficientes para determinar a causa.";
            String summary = "Dados insuficientes para avaliação.";
            if (worst == 0) {
                primary = "Conexão dentro dos parâmetros.";
                summary = "Conexão estável.";
            } else if (worst > 0) {
                for (String metric : List.of("signal", "signal-to-noise", "tx-ccq", "rx-ccq")) {
                    if (!String.valueOf(worst).equals(row.get(metric + "-severity"))) continue;
                    primary = switch (metric) {
                        case "signal" -> "Sinal abaixo do recomendado.";
                        case "signal-to-noise" -> "SNR comprometido.";
                        case "tx-ccq" -> "TX CCQ reduzido sob tráfego significativo.";
                        default -> "RX CCQ reduzido sob tráfego significativo.";
                    };
                    summary = metric.endsWith("ccq") ? "Degradação identificada sob tráfego." : primary;
                    break;
                }
            }
            row.put("quality-primary-reason", primary);
            row.put("quality-summary", summary);
            row.put("quality-reason", "Qualidade: " + row.get("quality") + "\n" + String.join("\n", reasons)
                + (worst < 0 ? "\nSem indicadores válidos." : "\nMotivo: pior condição entre os indicadores disponíveis.")
                + (now - s.started < (window - 1L) * 1000 ? "\nProvisório: janela em formação." : ""));
            row.put("critical-alert", String.join("; ", alerts));
            row.put("connection-status", "connected");
            s.last = row; s.seen = now; s.connected = true;
            result.add(row);
        }
        for (var entry : stations.entrySet()) {
            if (!entry.getKey().startsWith(ap + "\u0000") || present.contains(entry.getKey())) continue;
            var s = entry.getValue();
            if (now < s.seen) continue;
            s.connected = false;
            var row = new LinkedHashMap<>(s.last);
            row.put("connection-status", "disconnected"); row.put("critical-alert", "");
            result.add(row);
        }
        return result;
    }
}
