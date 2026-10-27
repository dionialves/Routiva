package br.com.scriptping;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class IncrementalPingHistoryTests {
    @Test
    void incrementalWindowsMatchFullScanAcrossLateRowsErrorsAndExpirations() {
        var policy = new PacketLossPolicy();
        var analyzer = new PacketLossAnalyzer(policy, 1000, 2000);
        var incremental = analyzer.newIncrementalHistory();
        var reference = new RollingPingHistory();
        long now = 100_000_000L;
        var random = new Random(1234);
        long id = 0;
        for (int cycle = 0; cycle < 400; cycle++) {
            now += 10000;
            analyzer.advance(incremental, now);
            List<PingSample> batch = new ArrayList<>();
            for (int i = 0; i < 30; i++) {
                long time = now - random.nextInt(cycle < 20 ? 29_000_000 : 120_000);
                int outcome = random.nextInt(5);
                batch.add(new PingSample(++id, "h", "10.0.0.1", time,
                    outcome > 1 ? random.nextDouble() * 50 : null,
                    outcome == 0 ? "Tempo esgotado" : outcome == 1 ? "internal" : null));
            }
            for (var sample : batch) {
                reference.add(sample);
                if (sample.measuredAt() > now - analyzer.lookbackMs()) incremental.add(sample);
            }
            incremental.expire(now - analyzer.lookbackMs());
            reference.prune(now - analyzer.lookbackMs() + 1);
            var actual = analyzer.analyze(incremental, now);
            var expected = analyzer.analyze(reference, now);
            assertThat(actual).usingRecursiveComparison()
                .withComparatorForType((a,b) -> Math.abs(a-b) < 1e-8 ? 0 : Double.compare(a,b), Double.class)
                .isEqualTo(expected);
        }
    }

    @Test
    void orderedLargeHistoryAndLossRunsAcrossBlocksMatchReference() {
        var analyzer = new PacketLossAnalyzer(new PacketLossPolicy(), 1000, 2000);
        var incremental = analyzer.newIncrementalHistory();
        var reference = new RollingPingHistory();
        long now = 100_000_000L;
        for (int i = 0; i < 30000; i++) {
            boolean loss = i % 1000 < 600;
            var sample = new PingSample(i+1, "h", "10.0.0.1", now - 30_000_000L + i * 1000,
                loss ? null : 20d, loss ? "Tempo esgotado" : null);
            incremental.add(sample); reference.add(sample);
        }
        incremental.expire(now - analyzer.lookbackMs());
        reference.prune(now - analyzer.lookbackMs() + 1);
        assertThat(analyzer.analyze(incremental, now)).isEqualTo(analyzer.analyze(reference, now));
    }

    @Test
    void errorsNearStartRespectCoverageClippingAndEmptyWindows() {
        var analyzer = new PacketLossAnalyzer(new PacketLossPolicy(), 1000, 2000);
        var incremental = analyzer.newIncrementalHistory();
        var reference = new RollingPingHistory();
        long now = 100_000_000L;
        for (int i = 1; i < 15; i++) {
            var sample = new PingSample(i, "h", "10.0.0.1", now - 60000 + i * 10,
                i % 2 == 0 ? 10d : null, i % 2 == 0 ? null : "internal");
            incremental.add(sample); reference.add(sample);
        }
        assertThat(analyzer.analyze(incremental, now)).isEqualTo(analyzer.analyze(reference, now));
        incremental.expire(now);
        assertThat(analyzer.analyze(incremental, now).stats().attemptsEightHours()).isZero();
    }
}
