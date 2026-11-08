package br.com.scriptping.availability;

import br.com.scriptping.aggregation.MinuteAggregationService;
import br.com.scriptping.zabbix.Host;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AvailabilityIncrementalTests extends MinuteTestSupport {
    void aggregateAt(long time) {
        long limit=boundary();
        do { summaries.aggregate(limit,1000,time); } while(summaries.progress().cursor()<limit);
    }
    AvailabilitySnapshotRepository repository() { return new AvailabilitySnapshotRepository(jdbc,new ObjectMapper()); }
    void persist(AvailabilitySnapshot snapshot) { var repo=repository(); repo.save(snapshot,repo.encode(snapshot)); }

    void same(AvailabilitySnapshot actual,AvailabilitySnapshot full) {
        assertThat(actual.to()).isEqualTo(full.to());
        assertThat(actual.hosts()).isEqualTo(full.hosts());
        assertThat(actual.load()).isEqualTo(full.load());
        for (int size : AvailabilityDashboard.MINUTES) {
            var a=actual.periods().get(size); var b=full.periods().get(size);
            assertThat(a.from()).isEqualTo(b.from()); assertThat(a.intervals()).isEqualTo(b.intervals());
            assertThat(a.hosts().keySet()).isEqualTo(b.hosts().keySet());
            for (String id : a.hosts().keySet()) {
                var x=a.hosts().get(id); var y=b.hosts().get(id);
                assertThat(x.status()).as("status %s/%d",id,size).isEqualTo(y.status());
                assertThat(x.fresh()).isEqualTo(y.fresh()); assertThat(x.collectorHealthy()).isEqualTo(y.collectorHealthy());
                assertThat(x.retainedFrom()).as("earliest %s/%d",id,size).isEqualTo(y.retainedFrom());
                observation(x.total(),y.total());
                for(int i=0;i<x.points().size();i++) observation(x.points().get(i),y.points().get(i));
            }
        }
    }
    void observation(AvailabilitySnapshot.Observation a,AvailabilitySnapshot.Observation b) {
        assertThat(a.healthy()).isEqualTo(b.healthy()); assertThat(a.degraded()).isEqualTo(b.degraded());
        assertThat(a.lost()).isEqualTo(b.lost()); assertThat(a.errors()).isEqualTo(b.errors());
        assertThat(a.validMinutes()).isEqualTo(b.validMinutes()); assertThat(a.lastSampleAt()).isEqualTo(b.lastSampleAt());
        assertThat(a.latencySum()).isCloseTo(b.latencySum(),within(0.000001));
    }

    @Test void advancesAllPeriodsWithOnlySmallSharedRangesAndReplaysLateCorrectionsOnce() {
        insert(end-5*60000,10d,null);
        insert(end-86400000L,30d,null);
        insert(end-100*86400000L,null,"Coletor: SEND_ERROR");
        insert(end-60000,null,"Tempo esgotado");
        aggregateAt(now);
        var initial=queries.advanceAt(List.of(host),null,now); persist(initial);
        insert(end-100*86400000L,11d,null); // An error-only old minute becomes valid.
        insert(end-5*60000,null,"Tempo esgotado"); // Correction in the outgoing range.
        insert(end+5000,5d,null);
        aggregateAt(now+60000);
        var instrumented=mock(MinuteAggregationService.class,org.mockito.AdditionalAnswers.delegatesTo(summaries));
        var updater=new PacketLossAnalysisService(instrumented,new PacketLossPolicy(),48,60000);
        var next=updater.advanceAt(List.of(host),initial,now+60000);
        var reads=mockingDetails(instrumented).getInvocations().stream()
            .filter(call -> call.getMethod().getName().equals("historicalBuckets")).toList();
        assertThat(reads).hasSizeLessThanOrEqualTo(20);
        for (var call : reads) {
            @SuppressWarnings("unchecked") var intervals=(List<AvailabilityPeriod.Interval>)call.getArgument(0);
            assertThat(intervals).hasSize(1);
            assertThat(intervals.getFirst().to()-intervals.getFirst().from()).isLessThanOrEqualTo(60000);
        }
        same(next,queries.buildAt(List.of(host),AvailabilityDashboard.MINUTES,now+60000));
        persist(next);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM availability_change",Long.class)).isZero();
        var restored=repository().load();
        var repeat=queries.advanceAt(List.of(host),restored,now+60000);
        same(repeat,queries.buildAt(List.of(host),AvailabilityDashboard.MINUTES,now+60000));
    }

    @Test void openMinuteSurvivesAcknowledgementAndExpiredNewestMeasurementBecomesNoData() {
        insert(end-5*60000,10d,null);
        insert(end+5000,8d,null); // Source persists it, but it is not in the first report.
        aggregateAt(now);
        var first=queries.advanceAt(List.of(host),null,now); persist(first);
        aggregateAt(now+60000);
        var second=queries.advanceAt(List.of(host),first,now+60000);
        assertThat(second.periods().get(5).hosts().get(host.id()).total().healthy()).isEqualTo(1);
        same(second,queries.buildAt(List.of(host),AvailabilityDashboard.MINUTES,now+60000)); persist(second);
        aggregateAt(now+6*60000);
        var third=queries.advanceAt(List.of(host),repository().load(),now+6*60000);
        assertThat(third.periods().get(5).hosts().get(host.id()).total().lastSampleAt()).isNull();
        same(third,queries.buildAt(List.of(host),AvailabilityDashboard.MINUTES,now+6*60000));
    }

    @Test void inventoryIpAndMetadataChangesOnlyBootstrapNewEndpoints() {
        insert(end-60000,10d,null); aggregateAt(now);
        var first=queries.advanceAt(List.of(host),null,now); persist(first);
        var newHost=new Host("new","New","127.0.0.2",List.of());
        var moved=new Host(host.id(),"Renamed","127.0.0.3",List.of(new Host.Group("new-group","New group")));
        insert(newHost,end-120000,null,"Tempo esgotado",20);
        insert(moved,end-60000,9d,null,20);
        aggregateAt(now+60000);
        var selection=List.of(moved,newHost);
        var next=queries.advanceAt(selection,first,now+60000);
        same(next,queries.buildAt(selection,AvailabilityDashboard.MINUTES,now+60000)); persist(next);
        var renamed=new Host(moved.id(),"Renamed again",moved.ip(),List.of());
        var removed=queries.advanceAt(List.of(renamed),next,now+60000);
        same(removed,queries.buildAt(List.of(renamed),AvailabilityDashboard.MINUTES,now+60000));
    }

    @Test void publishingCapturedRevisionNeverAcknowledgesNewerConcurrentChanges() {
        insert(end-60000,10d,null); aggregateAt(now);
        var captured=queries.advanceAt(List.of(host),null,now);
        insert(end-60000,null,"Tempo esgotado"); aggregateAt(now);
        persist(captured); // Simulates ingestion committing between build and publication.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM availability_change",Long.class)).isEqualTo(1);
        var next=queries.advanceAt(List.of(host),repository().load(),now);
        same(next,queries.buildAt(List.of(host),AvailabilityDashboard.MINUTES,now));
        assertThat(next.periods().get(5).hosts().get(host.id()).total().lost()).isEqualTo(1);
    }

    @Test void failedPublicationRollsBackPayloadCheckpointAndJournalTogether() {
        context.registerBean(AvailabilitySnapshotRepository.class,
            () -> new AvailabilitySnapshotRepository(jdbc,new ObjectMapper()));
        var repo=context.getBean(AvailabilitySnapshotRepository.class);
        insert(end-60000,10d,null); aggregateAt(now);
        var previous=queries.advanceAt(List.of(host),null,now);
        repo.save(previous,repo.encode(previous));
        insert(end-30000,null,"Tempo esgotado"); aggregateAt(now);
        var next=queries.advanceAt(List.of(host),previous,now);
        var invalid=new AvailabilitySnapshot(next.format(),next.version(),-1,next.to(),next.load(),next.hosts(),next.periods());
        var payload=repo.encode(invalid);
        jdbc.execute("ALTER TABLE availability_snapshot ADD CONSTRAINT valid_snapshot_time CHECK(built_at>=0)");
        try {
            assertThatThrownBy(() -> repo.save(invalid,payload)).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(repo.load().version()).isEqualTo(previous.version());
            assertThat(jdbc.queryForObject("SELECT floor_revision FROM availability_change_state WHERE id=1",Long.class))
                .isEqualTo(previous.load().liveCursor());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM availability_change",Long.class)).isEqualTo(1);
        } finally { jdbc.execute("ALTER TABLE availability_snapshot DROP CONSTRAINT valid_snapshot_time"); }
        repo.save(next,repo.encode(next));
        assertThat(repo.load().version()).isEqualTo(next.version());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM availability_change",Long.class)).isZero();
    }

    @Test void journalGapRebuildsInsteadOfSilentlyLosingContributions() {
        insert(end-60000,10d,null); aggregateAt(now);
        var first=queries.advanceAt(List.of(host),null,now);
        insert(end-60000,null,"Tempo esgotado"); aggregateAt(now);
        jdbc.update("DELETE FROM availability_change");
        jdbc.update("UPDATE availability_change_state SET floor_revision=? WHERE id=1",summaries.progress().cursor());
        same(queries.advanceAt(List.of(host),first,now),queries.buildAt(List.of(host),AvailabilityDashboard.MINUTES,now));
    }

    @Test void unknownHostGetsExactFirstMeasurementAfterPreviouslyEmptyCache() {
        aggregateAt(now);
        var first=queries.advanceAt(List.of(host),null,now); persist(first);
        insert(end+30000,10d,null); aggregateAt(now+60000);
        var next=queries.advanceAt(List.of(host),first,now+60000);
        same(next,queries.buildAt(List.of(host),AvailabilityDashboard.MINUTES,now+60000));
    }

    @Test void calendarClampingLeapDayAndMondayBoundaryMatchFullRebuild() {
        for (String time : List.of("2024-02-28T23:59:37-03:00","2024-03-30T23:59:37-03:00","2026-11-01T23:59:37-03:00")) {
            // Reset only this isolated fixture between independent calendar scenarios.
            jdbc.update("DELETE FROM ping_sample"); jdbc.update("DELETE FROM ping_minute"); jdbc.update("DELETE FROM ping_rollup");
            jdbc.update("DELETE FROM availability_change");
            jdbc.update("UPDATE availability_change_state SET floor_revision=0");
            jdbc.update("UPDATE ping_aggregation_progress SET cursor_id=0,boundary_id=0,processed_at=NULL,complete_through=0,rollups_ready=FALSE");
            long a=OffsetDateTime.parse(time).toInstant().toEpochMilli();
            long cutoff=Math.floorDiv(a,60000)*60000;
            for (long at : new long[]{cutoff-60000,cutoff-86400000L,cutoff-30*86400000L,cutoff-365*86400000L,cutoff+1000}) insert(at,7d,null);
            aggregateAt(a);
            var first=queries.advanceAt(List.of(host),null,a); persist(first);
            aggregateAt(a+60000);
            var next=queries.advanceAt(List.of(host),first,a+60000);
            same(next,queries.buildAt(List.of(host),AvailabilityDashboard.MINUTES,a+60000));
        }
    }
}
