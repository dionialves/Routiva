package br.com.scriptping;

import static br.com.scriptping.PacketLossAnalysis.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.*;
import org.junit.jupiter.api.Test;

class PacketLossTests {

    static final long BASE = 6_000_000;
    PacketLossPolicy rules = new PacketLossPolicy();
    PacketLossAnalyzer analyzer = new PacketLossAnalyzer(rules, 1000, 2000);

    RollingPingHistory history(
        int seconds,
        Set<Integer> losses,
        Set<Integer> errors,
        Set<Integer> gaps
    ) {
        var history = new RollingPingHistory();
        for (int i = 1; i <= seconds; i++) {
            if (gaps.contains(i)) continue;
            history.add(
                new PingSample(
                    i,
                    "host",
                    "10.0.0.1",
                    BASE + i * 1000L,
                    losses.contains(i) || errors.contains(i) ? null : 10d,
                    losses.contains(i)
                        ? "Tempo esgotado"
                        : errors.contains(i)
                          ? "Falha ao executar ping"
                          : null
                )
            );
        }
        return history;
    }

    PacketLossAnalysis analyze(int seconds, Set<Integer> losses) {
        return analyzer.analyze(
            history(seconds, losses, Set.of(), Set.of()),
            BASE + seconds * 1000L
        );
    }

    @Test
    void noLossDuringAFullHour() {
        var result = analyze(3600, Set.of());
        assertThat(result.minute()).isEqualTo(Quality.NORMAL);
        assertThat(result.hour()).isEqualTo(Quality.NORMAL);
        assertThat(result.level()).isEqualTo(Level.NORMAL);
        assertThat(result.stats().attemptsLastHour()).isEqualTo(3600);
    }

    @Test
    void minuteDiagnosticUsesPercentagesWithoutChangingWindowStatus() {
        var recent = analyze(3600, Set.of(3590));
        assertThat(recent.minute()).isEqualTo(Quality.CRITICO);
        assertThat(recent.hour()).isEqualTo(Quality.NORMAL);
        assertThat(recent.level()).isEqualTo(Level.NORMAL);
        var old = analyze(3600, Set.of(600));
        assertThat(old.minute()).isEqualTo(Quality.NORMAL);
        assertThat(old.hour()).isEqualTo(Quality.NORMAL);
        assertThat(old.stats().lossesLastHour()).isEqualTo(1);
        assertThat(old.stats().lossesLastMinute()).isZero();
    }

    @Test
    void anInternalFailureBreaksTheConfirmedLossSequence() {
        var result = analyzer.analyze(
            history(5, Set.of(1, 3, 4), Set.of(2), Set.of()),
            BASE + 5000
        );
        assertThat(result.maxHourSequence()).isEqualTo(2);
        assertThat(result.stats().lossesLastHour()).isEqualTo(3);
        assertThat(result.stats().attemptsLastHour()).isEqualTo(4);
        assertThat(result.level()).isEqualTo(Level.NORMAL);
    }

    @Test
    void internalErrorsAndCollectionGapsDoNotCountAsLossOrRecovery() {
        var errors = analyzer.analyze(
            history(603, Set.of(300, 301, 302), Set.of(500), Set.of()),
            BASE + 603000
        );
        assertThat(errors.stats().lossesLastHour()).isEqualTo(3);
        assertThat(errors.stats().attemptsLastHour()).isEqualTo(602);
        assertThat(errors.level()).isEqualTo(Level.NORMAL);
        assertThat(errors.recoveryConfirmed()).isFalse();
        Set<Integer> gaps = new HashSet<>();
        for (int i = 400; i <= 550; i++) gaps.add(i);
        var interrupted = analyzer.analyze(
            history(603, Set.of(300, 301, 302), Set.of(), gaps),
            BASE + 603000
        );
        assertThat(interrupted.level()).isEqualTo(Level.NORMAL);
        assertThat(interrupted.recoveryConfirmed()).isFalse();
    }

    @Test
    void totalUnavailabilityOrNoMoreSamplesCannotBecomeRecovered() {
        var history = history(3, Set.of(1, 2, 3), Set.of(), Set.of());
        var result = analyzer.analyze(history, BASE + 3600000);
        assertThat(result.fresh()).isFalse();
        assertThat(result.recoveryConfirmed()).isFalse();
        assertThat(result.level()).isEqualTo(Level.NORMAL);
        assertThat(result.minute()).isEqualTo(Quality.DADOS_INSUFICIENTES);
    }

    @Test
    void incompleteDataIsExplicitAndAbsentPacketsAreNotInvented() {
        var result = analyzer.analyze(history(12, Set.of(10), Set.of(), Set.of()), BASE + 60000);
        assertThat(result.minute()).isEqualTo(Quality.DADOS_INSUFICIENTES);
        assertThat(result.stats().attemptsLastMinute()).isEqualTo(12);
        assertThat(result.stats().lossesLastMinute()).isEqualTo(1);
        var empty = analyzer.analyze(new RollingPingHistory(), BASE);
        assertThat(empty.minute()).isEqualTo(Quality.DADOS_INSUFICIENTES);
        assertThat(empty.stats().attemptsLastMinute()).isZero();
    }

    @Test
    void cadenceChangesScaleMinuteThresholdsToRealPacketCounts() {
        var history = new RollingPingHistory();
        for (int i = 1; i <= 12; i++) history.add(
            new PingSample(
                i,
                "host",
                "10.0.0.1",
                BASE + i * 5000L,
                i == 11 ? null : 10d,
                i == 11 ? "Tempo esgotado" : null
            )
        );
        var result = new PacketLossAnalyzer(rules, 5000, 2000).analyze(history, BASE + 60000);
        assertThat(result.minute()).isEqualTo(Quality.CRITICO);
        assertThat(result.stats().attemptsLastMinute()).isEqualTo(12);
        assertThat(result.level()).isEqualTo(Level.NORMAL);
    }

    @Test
    void slidingWindowsExpireOldSamplesAndHandleDuplicatesLateAndFutureResults() {
        var history = new RollingPingHistory();
        var old = new PingSample(1, "host", "10.0.0.1", BASE + 1000, null, "Tempo esgotado");
        history.add(new PingSample(2, "host", "10.0.0.1", BASE + 2000, 10d, null));
        assertThat(history.add(old)).isTrue();
        assertThat(history.add(old)).isFalse();
        history.add(new PingSample(3, "host", "10.0.0.1", BASE + 99999999, null, "Tempo esgotado"));
        var before = analyzer.analyze(history, BASE + 3600000);
        assertThat(before.stats().attemptsLastHour()).isEqualTo(2);
        assertThat(before.stats().lossesLastHour()).isEqualTo(1);
        var after = analyzer.analyze(history, BASE + 3601000);
        assertThat(after.stats().lossesLastHour()).isZero();
        assertThat(after.stats().attemptsLastHour()).isEqualTo(1);
    }

    @Test
    void consecutiveAndRecurringLossesDoNotCreateIndependentAlerts() {
        var result = analyze(3600, Set.of(3440, 3460, 3500, 3520, 3598, 3599, 3600));
        assertThat(result.recurring()).isFalse();
        assertThat(result.level()).isEqualTo(Level.NORMAL);
        assertThat(result.hour()).isEqualTo(Quality.NORMAL);
        assertThat(
            rules.windowQuality(
                result.stats().lossesFiveMinutes(),
                result.stats().attemptsFiveMinutes()
            )
        ).isEqualTo(Quality.CRITICO);
    }

    @Test
    void classificationDropsAsSoonAsWindowPercentageDropsWithoutRecoveryTimer() {
        var samples = history(600, Set.of(1, 2, 3), Set.of(), Set.of());
        var before = analyzer.analyze(samples, BASE + 300000);
        var after = analyzer.analyze(samples, BASE + 301000);
        assertThat(
            rules.windowQuality(
                before.stats().lossesFiveMinutes(),
                before.stats().attemptsFiveMinutes()
            )
        ).isEqualTo(Quality.CRITICO);
        assertThat(
            rules.windowQuality(
                after.stats().lossesFiveMinutes(),
                after.stats().attemptsFiveMinutes()
            )
        ).isEqualTo(Quality.ATENCAO);
    }
}
