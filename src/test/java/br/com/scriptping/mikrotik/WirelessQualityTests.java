package br.com.scriptping.mikrotik;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WirelessQualityTests {
    private WirelessQuality engine() {
        return new WirelessQuality(30, 120000, new double[]{-65,-75,-83}, new double[]{30,25,15}, new double[]{90,80,60});
    }
    private Map<String,String> row(String signal) {
        var row = new HashMap<String,String>();
        row.put("interface", "wlan1"); row.put("mac-address", "AA:BB:CC:DD:EE:FF");
        if (signal != null) row.put("signal-strength", signal);
        return row;
    }
    @Test void immediateAndRollingWindow() {
        var engine = engine();
        assertEquals("Normal", engine.update("ap", List.of(row("-60")), 0).getFirst().get("quality"));
        Map<String,String> last = null;
        for (int i=1; i<=30; i++) last = engine.update("ap", List.of(row("-80")), i*1000).getFirst();
        assertEquals("-80.00", last.get("avg-signal"));
        assertEquals("Ruim", last.get("quality"));
    }
    @Test void instantAlertDoesNotReplaceMean() {
        var engine = engine();
        for (int i=0; i<29; i++) engine.update("ap", List.of(row("-60")), i*1000);
        var critical = engine.update("ap", List.of(row("-90")), 30000).getFirst();
        assertEquals("Normal", critical.get("quality"));
        assertFalse(critical.get("critical-alert").isEmpty());
        assertEquals("", engine.update("ap", List.of(row("-60")), 31000).getFirst().get("critical-alert"));
    }
    @Test void missingAndInvalidAreNotZeroAndWorstMetricWins() {
        var engine = engine();
        assertEquals("Indisponível", engine.update("ap", List.of(row(null)), 0).getFirst().get("quality"));
        var row = row("-45dBm@6Mbps"); row.put("signal-to-noise", "19"); row.put("tx-ccq", "101%");
        var result = engine.update("ap", List.of(row), 1000).getFirst();
        assertEquals("Ruim", result.get("quality")); assertNull(result.get("avg-tx-ccq"));
        assertTrue(result.get("quality-reason").contains("Sinal forte"));
    }
    @Test void disconnectPreservesAndReconnectResetsAndApIsIsolated() {
        var engine = engine();
        engine.update("ap", List.of(row("-90")), 0);
        var gone = engine.update("ap", List.of(), 1000).getFirst();
        assertEquals("disconnected", gone.get("connection-status")); assertEquals("-90.00", gone.get("avg-signal"));
        assertEquals("-60.00", engine.update("ap", List.of(row("-60")), 2000).getFirst().get("avg-signal"));
        assertEquals("-70.00", engine.update("other", List.of(row("-70")), 3000).getFirst().get("avg-signal"));
        assertTrue(engine.update("ap", List.of(), 123000).isEmpty());
    }
    @Test void uptimeRollbackResetsAssociationAndRatesAreAuxiliary() {
        var engine = engine(); var first = row("-90"); first.put("uptime", "1h");
        engine.update("ap", List.of(first), 0);
        var next = row("-60"); next.put("uptime", "1s"); next.put("tx-rate", "6.5Mbps-20MHz/1S");
        var result = engine.update("ap", List.of(next), 1000).getFirst();
        assertEquals("Normal", result.get("quality")); assertEquals("6.50", result.get("avg-tx-rate"));
    }
    @Test void fractionalThresholdsHaveNoGaps() {
        var engine = engine();
        for (var entry : Map.of("-65", "Normal", "-65.1", "Atenção", "-75.1", "Ruim", "-82.5", "Ruim", "-83", "Crítico").entrySet())
            assertEquals(entry.getValue(), engine.update(entry.getKey(), List.of(row(entry.getKey())), 0).getFirst().get("quality"));
    }
    @Test void snrAndCcqBoundariesAndValidSampleWindows() {
        for (String metric : List.of("signal-to-noise", "tx-ccq", "rx-ccq")) {
            var values = metric.equals("signal-to-noise")
                ? Map.of("30", "Normal", "29.9", "Atenção", "24.9", "Ruim", "14.9", "Crítico")
                : Map.of("90", "Normal", "89.9", "Atenção", "79.9", "Ruim", "59.9", "Crítico");
            for (var entry : values.entrySet()) {
                var row = row(null); row.put(metric, entry.getKey());
                assertEquals(entry.getValue(), engine().update("ap", List.of(row), 0).getFirst().get("quality"));
            }
        }
        var engine = engine();
        engine.update("ap", List.of(row("-60")), 0);
        assertNull(engine.update("ap", List.of(row(null)), 1000).getFirst().get("avg-signal"));
        assertEquals("-70.00", engine.update("ap", List.of(row("-80")), 2000).getFirst().get("avg-signal"));
    }

}
