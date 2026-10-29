package br.com.scriptping;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class MinuteReportTests extends MinuteTestSupport {
    @Test void weightedMeansExactMinuteBoundariesEmptyMinutesAndErrors() {
        insert(end-300000,10d,null);insert(end-120000,30d,null);insert(end-120000,50d,null);
        insert(end-120000,null,"Tempo esgotado");insert(end-120000,null,"Coletor: SEND_ERROR");
        insert(end,100d,null); // Open minute is persisted but not included in completed-minute reports.
        drain(2);
        jdbc.update("DELETE FROM ping_sample"); // All request data must come exclusively from summaries.
        var view=queries.viewAt(List.of(host),5,now);
        assertThat(view.report().series()).hasSize(5);
        assertThat(view.report().from()).isEqualTo(end-300000);
        assertThat(view.report().to()).isEqualTo(end);
        assertThat(view.report().total().totalPings()).isEqualTo(5);
        assertThat(view.report().total().latency()).isEqualTo(30);
        assertThat(view.report().total().technical()).isEqualTo(75);
        assertThat(view.report().total().errors()).isEqualTo(1);
        assertThat(view.report().series().get(1).metrics().latency()).isNull();
        assertThat(view.hosts().getFirst().status()).isEqualTo("CRITICO");
        assertThat(view.report().series().stream().mapToLong(p->p.metrics().totalPings()).sum()).isEqualTo(5);
    }
    @Test void queryingAnotherPeriodAndRestartNeedNoCheckpointOrWarmup() {
        insert(end-2*3600000L,12d,null);insert(end-10000,8d,null);drain(10);
        var restarted=new PacketLossAnalysisService(new MinuteAggregationStore(jdbc,manager),new PacketLossPolicy(),48,60000);
        assertThat(restarted.viewAt(List.of(host),60,now).report().total().latency()).isEqualTo(8);
        assertThat(restarted.viewAt(List.of(host),480,now).report().total().latency()).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE LOWER(table_name) LIKE 'analytics_checkpoint%'",Long.class)).isZero();
    }
    @Test void groupTotalsAreWeightedAndOnlySelectedHostsAreIncluded() {
        var b=new Host("b","Host B","127.0.0.2",List.of());
        insert(end-10000,10d,null);insert(b,end-10000,30d,null,20);insert(b,end-10000,null,"Tempo esgotado",20);drain(10);
        assertThat(queries.viewAt(List.of(host,b),5,now).report().total().latency()).isEqualTo(20);
        assertThat(queries.viewAt(List.of(host),5,now).report().total().operational()).isEqualTo(100);
        assertThat(queries.viewAt(List.of(),5,now).report().total().technical()).isNull();
    }
    @Test void backlogAndStaleDataRemainExplicit() {
        insert(end-60000,10d,null);insert(end-30000,10d,null);
        summaries.aggregate(boundary(),1,now);
        var partial=queries.viewAt(List.of(host),5,now);
        assertThat(partial.report().load().phase()).isEqualTo("CATCHING_UP");
        drain(1);
        assertThat(queries.viewAt(List.of(host),5,now+300000).hosts().getFirst().fresh()).isFalse();
    }
    @Test void lateRowsCorrectAlreadyQueriedPeriodWithoutCacheInvalidation() {
        insert(end-60000,10d,null);drain(10);
        assertThat(queries.viewAt(List.of(host),5,now).report().total().loss()).isZero();
        insert(end-50000,null,"Tempo esgotado");drain(10);
        assertThat(queries.viewAt(List.of(host),5,now).report().total().loss()).isEqualTo(50);
    }
    @Test void classificationHonorsConfiguredLossThresholdsAndHistoricalLimit() {
        var rules=new PacketLossPolicy();rules.setHourNormalMaxPercent(40);rules.setHourCriticalMinPercent(80);
        insert(host,end-2000,10d,null,5);insert(end-1000,null,"Tempo esgotado");drain(10);
        var configured=new PacketLossAnalysisService(summaries,rules,48,60000);
        var view=configured.viewAt(List.of(host),5,now);
        assertThat(view.hosts().getFirst().status()).isEqualTo("DEGRADADO");
        assertThat(view.report().total().degraded()).isEqualTo(1);
        assertThat(view.report().total().operational()).isZero();
    }
}
