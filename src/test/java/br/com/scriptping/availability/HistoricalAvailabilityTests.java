package br.com.scriptping.availability;

import br.com.scriptping.zabbix.Host;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class HistoricalAvailabilityTests extends MinuteTestSupport {
    @Test void allPeriodsHaveBoundedContiguousIntervalsAndCorrectTotals() {
        insert(end - 120000, 10d, null);
        insert(end - 120000, null, "Tempo esgotado");
        drain(1);
        for (var period : AvailabilityPeriod.OPTIONS) {
            var report = queries.viewAt(List.of(host), period.minutes(), now).report();
            assertThat(report.series()).hasSizeBetween(5, 50);
            assertThat(report.total().technical()).isEqualTo(50d);
            assertThat(report.series().stream().mapToLong(p -> p.metrics().totalPings()).sum()).isEqualTo(2);
            assertThat(report.series().getFirst().from()).isEqualTo(report.from());
            assertThat(report.series().getLast().to()).isEqualTo(report.to());
            for (int i = 1; i < report.series().size(); i++)
                assertThat(report.series().get(i).from()).isEqualTo(report.series().get(i-1).to());
            assertThat(report.observedMillis()).isEqualTo(60000L);
        }
    }

    @Test void retainedHistorySurvivesCleanupAndUsesWeightedCalendarMonths() {
        long old = Instant.parse("2025-12-15T12:00:00Z").toEpochMilli();
        insert(old, 10d, null);
        insert(old + 60000, 30d, null);
        insert(old + 60000, null, "Tempo esgotado");
        insert(end - 60000, 20d, null);
        drain(1);
        retention.cleanup(end - 172800000L);
        var report = queries.viewAt(List.of(host), 525600, now).report();
        assertThat(report.total().technical()).isEqualTo(75d);
        assertThat(report.total().latency()).isEqualTo(20d);
        assertThat(report.observedMillis()).isEqualTo(180000L);
        assertThat(report.series()).hasSize(13);
        assertThat(report.series().getFirst().partial()).isTrue();
        assertThat(report.series().getLast().partial()).isTrue();
        assertThat(report.series().stream().filter(p -> p.metrics().total() > 0)).hasSize(2);
        assertThat(queries.viewAt(List.of(host), 2880, now).report().total().total()).isEqualTo(1);
        assertThat(count("total_pings")).isEqualTo(4);
    }

    @Test void backfillRestartLateContributionsAndEmptyMinutesMatchMinuteSource() {
        long old = end - 100 * 86400000L;
        insert(old, null, "Coletor: SEND_ERROR");
        insert(old + 60000, 15d, null);
        drain(10);
        jdbc.update("DELETE FROM ping_rollup");
        jdbc.update("UPDATE ping_aggregation_progress SET rollups_ready=FALSE");
        // The fallback is valid even before the first migration transaction finishes.
        assertThat(queries.viewAt(List.of(host), 525600, now).report().total().totalPings()).isEqualTo(2);
        startAggregation();
        queries = new PacketLossAnalysisService(summaries,new PacketLossPolicy(),48,60000);
        insert(old, 5d, null); // Previously error-only minute gains its first valid observation.
        insert(old + 60000, 25d, null);
        drain(1);
        var accelerated = summaries.historicalTotals(old-60000,end,List.of(host),true);
        var source = summaries.historicalTotals(old-60000,end,List.of(host),false);
        assertThat(accelerated).isEqualTo(source);
        assertThat(accelerated.validHostMinutes()).isEqualTo(2);
        drain(10); // Reprocessing the same boundary must not double count.
        assertThat(summaries.historicalTotals(old-60000,end,List.of(host),true)).isEqualTo(source);
    }

    @Test void groupedBucketsMatchSourceAcrossAllPeriodsAndStorageResolutions() {
        var other = new Host("b", "Other", "127.0.0.2", List.of());
        long start = AvailabilityPeriod.of(1051200).from(end);
        for (long at : new long[]{start-60000, start, end-100*86400000L, end-7*86400000L,
            end-86400000L, end-3600000L, end-60000, end}) {
            insert(at,10d,null);
            insert(other,at,null,"Tempo esgotado",20);
        }
        drain(100);
        var hosts=List.of(host,other);
        for (var option : AvailabilityPeriod.OPTIONS) {
            var intervals=option.intervals(option.from(end),end);
            var grouped=summaries.historicalBuckets(intervals,hosts,true);
            for (int index=0; index<intervals.size(); index++) {
                var interval=intervals.get(index);
                var expected=summaries.historicalTotals(interval.from(),interval.to(),hosts,false);
                var actual=new HashMap<br.com.scriptping.aggregation.MinuteAggregationService.Key,
                    br.com.scriptping.aggregation.MinuteAggregationService.Summary>();
                long covered=0;
                for (var bucket : grouped) if (bucket.index()==index) {
                    actual.merge(bucket.summary().key(),bucket.summary(),br.com.scriptping.aggregation.MinuteAggregationService::combine);
                    covered+=bucket.validHostMinutes();
                }
                assertThat(actual).isEqualTo(expected.totals());
                assertThat(covered).isEqualTo(expected.validHostMinutes());
            }
        }
    }

    @Test void calendarMonthsLeapYearsAndMondayWeeksUseSaoPaulo() {
        long leap = ZonedDateTime.of(2024,2,29,13,17,0,0,AvailabilityPeriod.ZONE).toInstant().toEpochMilli();
        var year = AvailabilityPeriod.of(525600);
        assertThat(Instant.ofEpochMilli(year.from(leap)).atZone(AvailabilityPeriod.ZONE).toLocalDate())
            .isEqualTo(LocalDate.of(2023,2,28));
        var monthly = year.intervals(year.from(leap),leap);
        for (var interval : monthly) if (!interval.partial()) {
            var start = Instant.ofEpochMilli(interval.from()).atZone(AvailabilityPeriod.ZONE);
            assertThat(start.getDayOfMonth()).isEqualTo(1);
            assertThat(start.getHour()).isZero();
            assertThat(start.plusMonths(1).toInstant().toEpochMilli()).isEqualTo(interval.to());
        }
        var weeks = AvailabilityPeriod.of(129600);
        for (var interval : weeks.intervals(weeks.from(leap),leap)) if (!interval.partial())
            assertThat(Instant.ofEpochMilli(interval.from()).atZone(AvailabilityPeriod.ZONE).getDayOfWeek())
                .isEqualTo(DayOfWeek.MONDAY);
    }

    @Test void rollupsRespectHostTargetSelectionAndClipCalendarEdges() {
        var other = new Host("b", "Other", "127.0.0.2", List.of());
        long start = AvailabilityPeriod.of(525600).from(end);
        insert(start-60000, 1d, null);
        insert(start, 10d, null);
        insert(other,start,null,"Tempo esgotado",20);
        insert(end, 100d,null);
        drain(2);
        var report = queries.viewAt(List.of(host),525600,now).report();
        assertThat(report.total().total()).isEqualTo(1);
        assertThat(report.total().latency()).isEqualTo(10d);
        assertThat(queries.viewAt(List.of(host,other),525600,now).report().total().technical()).isEqualTo(50d);
    }
}
