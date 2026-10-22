package br.com.scriptping;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static br.com.scriptping.PacketLossAnalysis.*;

class PacketLossTests {
    static final long BASE=6_000_000;
    PacketLossPolicy rules=new PacketLossPolicy();
    PacketLossAnalyzer analyzer=new PacketLossAnalyzer(rules,1000,2000);
    RollingPingHistory history(int seconds,Set<Integer> losses,Set<Integer> errors,Set<Integer> gaps) {
        var history=new RollingPingHistory();
        for(int i=1;i<=seconds;i++) {
            if(gaps.contains(i))continue;
            history.add(new PingSample(i,"host","10.0.0.1",BASE+i*1000L,losses.contains(i)||errors.contains(i)?null:10d,losses.contains(i)?"Tempo esgotado":errors.contains(i)?"Falha ao executar ping":null));
        }
        return history;
    }
    PacketLossAnalysis analyze(int seconds,Set<Integer> losses) {return analyzer.analyze(history(seconds,losses,Set.of(),Set.of()),BASE+seconds*1000L);}

    @Test void noLossDuringAFullHour() {
        var result=analyze(3600,Set.of());
        assertThat(result.minute()).isEqualTo(Quality.NORMAL);assertThat(result.hour()).isEqualTo(Quality.NORMAL);
        assertThat(result.level()).isEqualTo(Level.NORMAL);assertThat(result.stats().attemptsLastHour()).isEqualTo(3600);
    }
    @Test void oneLossInMinuteOrHourIsNotOperationalDegradation() {
        var recent=analyze(3600,Set.of(3590));
        assertThat(recent.minute()).isEqualTo(Quality.EVENTO_ISOLADO);assertThat(recent.hour()).isEqualTo(Quality.NORMAL);
        assertThat(recent.level()).isEqualTo(Level.NORMAL);assertThat(recent.recentIssue()).isFalse();
        var old=analyze(3600,Set.of(600));
        assertThat(old.minute()).isEqualTo(Quality.NORMAL);assertThat(old.hour()).isEqualTo(Quality.NORMAL);
        assertThat(old.stats().lossesLastHour()).isEqualTo(1);assertThat(old.stats().lossesLastMinute()).isZero();
    }
    @Test void twoLossesOnlyClassifyAttention() {
        var result=analyze(3600,Set.of(3570,3590));
        assertThat(result.minute()).isEqualTo(Quality.ATENCAO);assertThat(result.hour()).isEqualTo(Quality.ATENCAO);
        assertThat(result.level()).isEqualTo(Level.NORMAL);
    }
    @Test void threeConsecutiveLossesAreImmediatelyCriticalEvenWithoutCoverage() {
        var result=analyze(3,Set.of(1,2,3));
        assertThat(result.minute()).isEqualTo(Quality.DADOS_INSUFICIENTES);
        assertThat(result.consecutive()).isEqualTo(3);assertThat(result.maxMinuteSequence()).isEqualTo(3);
        assertThat(result.level()).isEqualTo(Level.CRITICO);
    }
    @Test void sixDistributedLossesInOneMinuteAreCritical() {
        var result=analyze(3600,Set.of(3545,3555,3565,3575,3585,3595));
        assertThat(result.minute()).isEqualTo(Quality.CRITICO);assertThat(result.level()).isEqualTo(Level.CRITICO);
        assertThat(result.maxMinuteSequence()).isEqualTo(1);
    }
    @Test void recurrenceUsesTwoOfThreeClosedMinutesAndRecoversWithoutErasingHistory() {
        Set<Integer> losses=Set.of(3440,3460,3500,3520);
        var history=history(3821,losses,Set.of(),Set.of());
        var active=analyzer.analyze(history,BASE+3600000);
        assertThat(active.minute()).isEqualTo(Quality.NORMAL);assertThat(active.recurring()).isTrue();
        assertThat(active.level()).isEqualTo(Level.DEGRADADO);
        var recovered=analyzer.analyze(history,BASE+3821000);
        assertThat(recovered.level()).isEqualTo(Level.NORMAL);assertThat(recovered.recoveryConfirmed()).isTrue();
        assertThat(recovered.hour()).isEqualTo(Quality.DEGRADADO);assertThat(recovered.stats().lossesLastHour()).isEqualTo(4);
    }
    @Test void recurrenceNeverCountsTheCurrentIncompleteClockMinute() {
        var history=history(3600,Set.of(3500,3520,3550,3560),Set.of(),Set.of());
        assertThat(analyzer.analyze(history,BASE+3570000).recurring()).isFalse();
        assertThat(analyzer.analyze(history,BASE+3570000).level()).isEqualTo(Level.NORMAL);
        assertThat(analyzer.analyze(history,BASE+3600000).recurring()).isTrue();
    }
    @Test void anInternalFailureBreaksTheConfirmedLossSequence() {
        var result=analyzer.analyze(history(5,Set.of(1,3,4),Set.of(2),Set.of()),BASE+5000);
        assertThat(result.maxHourSequence()).isEqualTo(2);assertThat(result.stats().lossesLastHour()).isEqualTo(3);
        assertThat(result.stats().attemptsLastHour()).isEqualTo(4);assertThat(result.level()).isEqualTo(Level.NORMAL);
    }
    @Test void criticalColorRecoversOnlyAfterFiveContinuousMinutes() {
        var history=history(603,Set.of(300,301,302),Set.of(),Set.of());
        assertThat(analyzer.analyze(history,BASE+602000).level()).isEqualTo(Level.CRITICO);
        var recovered=analyzer.analyze(history,BASE+603000);
        assertThat(recovered.level()).isEqualTo(Level.NORMAL);assertThat(recovered.maxHourSequence()).isEqualTo(3);
        assertThat(recovered.stats().lossesLastHour()).isEqualTo(3);
    }
    @Test void internalErrorsAndCollectionGapsDoNotCountAsLossOrRecovery() {
        var errors=analyzer.analyze(history(603,Set.of(300,301,302),Set.of(500),Set.of()),BASE+603000);
        assertThat(errors.stats().lossesLastHour()).isEqualTo(3);assertThat(errors.stats().attemptsLastHour()).isEqualTo(602);
        assertThat(errors.level()).isEqualTo(Level.CRITICO);assertThat(errors.recoveryConfirmed()).isFalse();
        Set<Integer> gaps=new HashSet<>();for(int i=400;i<=550;i++)gaps.add(i);
        var interrupted=analyzer.analyze(history(603,Set.of(300,301,302),Set.of(),gaps),BASE+603000);
        assertThat(interrupted.level()).isEqualTo(Level.CRITICO);assertThat(interrupted.recoveryConfirmed()).isFalse();
    }
    @Test void totalUnavailabilityOrNoMoreSamplesCannotBecomeRecovered() {
        var history=history(3,Set.of(1,2,3),Set.of(),Set.of());
        var result=analyzer.analyze(history,BASE+3600000);
        assertThat(result.fresh()).isFalse();assertThat(result.recoveryConfirmed()).isFalse();
        assertThat(result.level()).isEqualTo(Level.CRITICO);assertThat(result.minute()).isEqualTo(Quality.DADOS_INSUFICIENTES);
    }
    @Test void incompleteDataIsExplicitAndAbsentPacketsAreNotInvented() {
        var result=analyzer.analyze(history(12,Set.of(10),Set.of(),Set.of()),BASE+60000);
        assertThat(result.minute()).isEqualTo(Quality.DADOS_INSUFICIENTES);
        assertThat(result.stats().attemptsLastMinute()).isEqualTo(12);assertThat(result.stats().lossesLastMinute()).isEqualTo(1);
        var empty=analyzer.analyze(new RollingPingHistory(),BASE);
        assertThat(empty.minute()).isEqualTo(Quality.DADOS_INSUFICIENTES);assertThat(empty.stats().attemptsLastMinute()).isZero();
    }
    @Test void cadenceChangesScaleMinuteThresholdsToRealPacketCounts() {
        var history=new RollingPingHistory();
        for(int i=1;i<=12;i++)history.add(new PingSample(i,"host","10.0.0.1",BASE+i*5000L,i==11?null:10d,i==11?"Tempo esgotado":null));
        var result=new PacketLossAnalyzer(rules,5000,2000).analyze(history,BASE+60000);
        assertThat(result.minute()).isEqualTo(Quality.DEGRADADO);assertThat(result.stats().attemptsLastMinute()).isEqualTo(12);
        assertThat(result.level()).isEqualTo(Level.NORMAL);
    }
    @Test void slidingWindowsExpireOldSamplesAndHandleDuplicatesLateAndFutureResults() {
        var history=new RollingPingHistory();
        var old=new PingSample(1,"host","10.0.0.1",BASE+1000,null,"Tempo esgotado");
        history.add(new PingSample(2,"host","10.0.0.1",BASE+2000,10d,null));
        assertThat(history.add(old)).isTrue();assertThat(history.add(old)).isFalse();
        history.add(new PingSample(3,"host","10.0.0.1",BASE+99999999,null,"Tempo esgotado"));
        var before=analyzer.analyze(history,BASE+3600000);
        assertThat(before.stats().attemptsLastHour()).isEqualTo(2);assertThat(before.stats().lossesLastHour()).isEqualTo(1);
        var after=analyzer.analyze(history,BASE+3601000);
        assertThat(after.stats().lossesLastHour()).isZero();assertThat(after.stats().attemptsLastHour()).isEqualTo(1);
    }
    @Test void restartReconstructionProducesTheSameClassificationAndRecovery() {
        var original=history(603,Set.of(300,301,302),Set.of(),Set.of());
        var restored=history(603,Set.of(300,301,302),Set.of(),Set.of());
        assertThat(analyzer.analyze(restored,BASE+602000)).isEqualTo(analyzer.analyze(original,BASE+602000));
        assertThat(analyzer.analyze(restored,BASE+603000).recoveryConfirmed()).isTrue();
    }
}
