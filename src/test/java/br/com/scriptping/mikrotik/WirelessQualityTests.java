package br.com.scriptping.mikrotik;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WirelessQualityTests {
    private WirelessQuality engine() {
        return new WirelessQuality(30, 120000, new double[]{-71,-75,-83}, new double[]{30,25,15}, new double[]{90,80,60},
                100000, 1000000, 60000, 2, -50, new double[]{1,1,2});
    }
    private Map<String,String> row(String signal) {
        var row = new HashMap<String,String>();
        row.put("interface", "wlan1"); row.put("mac-address", "AA:BB:CC:DD:EE:FF");
        if (signal != null) row.put("signal-strength", signal);
        return row;
    }
    @Test void presentationSummaryFollowsConfirmedWinnerAndIgnoresIdleCcq() {
        var engine = engine();
        var raw = row("-60"); raw.put("tx-ccq", "40"); raw.put("tx-bits-per-second", "0");
        var idle = engine.update("ap", List.of(raw), 0).getFirst();
        assertEquals("Conexão dentro dos parâmetros.", idle.get("quality-primary-reason"));
        raw.put("tx-bits-per-second", "3005410");
        var provisional = engine.update("ap", List.of(raw), 1000).getFirst();
        assertEquals("Conexão estável.", provisional.get("quality-summary"));
        var active = engine.update("ap", List.of(raw), 2000).getFirst();
        assertEquals("Crítico", active.get("quality"));
        assertEquals("TX CCQ reduzido sob tráfego significativo.", active.get("quality-primary-reason"));
        assertEquals("Degradação identificada sob tráfego.", active.get("quality-summary"));
        var missing = engine.update("other", List.of(row(null)), 0).getFirst();
        assertEquals("Dados insuficientes para avaliação.", missing.get("quality-summary"));
        var radio = row("-90"); radio.put("tx-ccq", "40"); radio.put("tx-bits-per-second", "0");
        var weak = engine.update("weak", List.of(radio), 0).getFirst();
        assertEquals("Sinal abaixo do recomendado.", weak.get("quality-primary-reason"));
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
    @Test void idleCcqDoesNotDegradeGoodSignalAndActiveCcqNeedsConfirmation() {
        var engine = engine();
        var row = row("-60");
        row.put("tx-ccq", "40");
        row.put("bytes", "0,0");
        assertEquals("Normal", engine.update("ap", List.of(row), 0).getFirst().get("quality"));
        row.put("bytes", "125000,0");
        assertEquals("Normal", engine.update("ap", List.of(row), 1000).getFirst().get("quality"));
        row.put("bytes", "250000,0");
        var result = engine.update("ap", List.of(row), 2000).getFirst();
        assertEquals("Crítico", result.get("quality"));
        assertEquals("0", result.get("signal-severity"));
        assertEquals("3", result.get("tx-ccq-severity"));
    }

    @Test void fractionalThresholdsHaveNoGaps() {
        var engine = engine();
        for (var entry : Map.of("-70", "Normal", "-70.5", "Normal", "-71", "Atenção", "-75", "Atenção", "-75.1", "Ruim", "-82.5", "Ruim", "-83", "Crítico").entrySet())
            assertEquals(entry.getValue(), engine.update(entry.getKey(), List.of(row(entry.getKey())), 0).getFirst().get("quality"));
    }
    @Test void snrAndCcqBoundariesAndValidSampleWindows() {
        for (String metric : List.of("signal-to-noise", "tx-ccq", "rx-ccq")) {
            var values = metric.equals("signal-to-noise")
                ? Map.of("30", "Normal", "29.9", "Atenção", "24.9", "Ruim", "14.9", "Crítico")
                : Map.of("90", "Normal", "89.9", "Atenção", "79.9", "Ruim", "59.9", "Crítico");
            for (var entry : values.entrySet()) {
                var row = row(null); row.put(metric, entry.getKey());
                row.put("tx-bits-per-second", "1000000");
                row.put("rx-bits-per-second", "1000000");
                var engine = engine();
                engine.update("ap", List.of(row), 0);
                assertEquals(entry.getValue(), engine.update("ap", List.of(row), 1000).getFirst().get("quality"));
            }
        }
        var engine = engine();
        engine.update("ap", List.of(row("-60")), 0);
        assertNull(engine.update("ap", List.of(row(null)), 1000).getFirst().get("avg-signal"));
        assertEquals("-70.00", engine.update("ap", List.of(row("-80")), 2000).getFirst().get("avg-signal"));
    }


    @Test void directionalActivityBoundariesAndHistoricalExpiry() {
        var engine = engine();
        var input = row("-60");
        input.put("tx-ccq", "58");
        input.put("rx-bits-per-second", "5000000");
        for (var rate : List.of("15000", "100000", "999999")) {
            input.put("tx-bits-per-second", rate);
            var result = engine.update("ap", List.of(input), Long.parseLong(rate) / 1000).getFirst();
            assertEquals("Normal", result.get("quality"));
            assertNull(result.get("tx-ccq-severity"));
            assertTrue(result.get("quality-reason").contains(rate.equals("15000") ? "ocioso" : "atividade baixa"));
        }
        input.put("tx-bits-per-second", "1000000");
        var first = engine.update("ap", List.of(input), 2000).getFirst();
        assertFalse(first.get("critical-alert").isEmpty());
        assertEquals("Normal", first.get("quality"));
        assertEquals("Crítico", engine.update("ap", List.of(input), 3000).getFirst().get("quality"));
        input.put("tx-bits-per-second", "0");
        input.put("tx-ccq", "12");
        var idle = engine.update("ap", List.of(input), 4000).getFirst();
        assertEquals("Normal", idle.get("quality"));
        assertNull(idle.get("active-tx-ccq"));
        assertTrue(idle.get("quality-reason").contains("Última avaliação válida TX: 58.00%"));
        assertFalse(engine.update("ap", List.of(input), 63000).getFirst().get("quality-reason").contains("Última avaliação"));
        input.put("tx-bits-per-second", "5000000");
        input.put("tx-ccq", "95");
        assertEquals("95.00", engine.update("ap", List.of(input), 64000).getFirst().get("active-tx-ccq"));
    }

    @Test void resetCounterSkipsIntervalAndInterruptsConfirmation() {
        var engine = engine();
        var input = row(null);
        input.put("rx-ccq", "30");
        long now = 0;
        for (String bytes : List.of("0,0", "0,125000", "0,0", "0,125000", "0,250000")) {
            input.put("bytes", bytes);
            var result = engine.update("ap", List.of(input), now).getFirst();
            assertEquals(now == 4000 ? "Crítico" : "Indisponível", result.get("quality"));
            now += 1000;
        }
    }

    @Test void recoveryNeedsMarginAndSparseSamplesExpireByTime() {
        var engine = engine();
        engine.update("ap", List.of(row("-72")), 0);
        assertEquals("Atenção", engine.update("ap", List.of(row("-70")), 1000).getFirst().get("quality"));
        assertEquals("Normal", engine.update("ap", List.of(row("-60")), 2000).getFirst().get("quality"));
        assertEquals("-82.00", engine.update("ap", List.of(row("-82")), 32000).getFirst().get("avg-signal"));
    }
}
