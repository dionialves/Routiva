package br.com.scriptping;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** Real SQL tests for the two disjoint readers, temporal edges and bounded publication. */
class ProgressiveBootstrapTests {
    final long now = 200_040_000L;
    final Host host = new Host("h", "Host", "10.0.0.1", List.of());
    JdbcTemplate jdbc;
    SampleStore store;
    PacketLossAnalysisService service;

    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        jdbc = new JdbcTemplate(ds);
        store = spy(new SampleStore(jdbc, 48, 600));
        service = new PacketLossAnalysisService(store, new PacketLossPolicy(), 1000, 2000);
        service.configureBootstrap(2, 1000);
    }
    @AfterEach void close() { service.closeCheckpointWriter(); }
    void add(long id, long time, Double latency) {
        jdbc.update("INSERT INTO ping_sample(id,host_id,target,measured_at,latency,error,latency_limit) VALUES(?,?,?,?,?,?,?)",
            id, host.id(), host.ip(), time, latency, latency == null ? "Tempo esgotado" : null, 20);
    }
    long count() { return service.availability(host, 48).total().total(); }

    @Test void recentFirstThenBackwardsWhileNewAndLateRowsAreCountedExactlyOnce() {
        add(1, now-600000, 10d); // older sample, regardless of temporal distance
        add(2, now-300000, 20d); // recent page crosses a five-minute boundary
        add(3, now-1, 30d);
        service.refreshAt(now); // latest two records, in descending measurement order
        assertThat(count()).isEqualTo(2);
        assertThat(service.loadStatus().loadedFrom()).isEqualTo(now-300000+1);
        assertThat(service.captureCheckpoint()).isNotNull();
        add(4, now+1, null);
        add(5, now-500000, 40d); // new ID with an old timestamp, read by live reader only
        service.refreshAt(now+10);
        assertThat(count()).isEqualTo(5);
        assertThat(service.current(List.of(host)).get(host.id()).stats().measuredAt()).isEqualTo(now+1);
        BootstrapTestSupport.refreshFully(service, now+10);
        assertThat(count()).isEqualTo(5);
        assertThat(service.availability(host, 48).total().lost()).isEqualTo(1);
        assertThat(service.loadStatus().complete()).isTrue();
        assertThat(service.captureCheckpoint().header().cursor()).isEqualTo(5);
    }

    @Test void defaultBatchStopsAtTenThousandEvenWithinOneMillisecond() {
        service.configureBootstrap(10000, 1000);
        var rows = new ArrayList<Object[]>();
        for (int id=1; id<=10001; id++) rows.add(new Object[]{id, host.id(), host.ip(), now-1, 10d});
        jdbc.batchUpdate("INSERT INTO ping_sample(id,host_id,target,measured_at,latency) VALUES(?,?,?,?,?)", rows);
        service.refreshAt(now);
        assertThat(count()).isEqualTo(10000);
        assertThat(service.loadStatus().loadedFrom()).isEqualTo(now); // timestamp still incomplete
        assertThat(service.loadStatus().complete()).isFalse();
        service.refreshAt(now);
        assertThat(count()).isEqualTo(10001);
        assertThat(service.loadStatus().complete()).isTrue();
    }

    @Test void sparseHistorySpanningFortyEightHoursCompletesInOnePage() {
        service.configureBootstrap(10000, 1000);
        add(1, now-47*3600000L, 10d);
        add(2, now-1, 20d);
        service.refreshAt(now);
        assertThat(count()).isEqualTo(2);
        assertThat(service.loadStatus().complete()).isTrue();
        verify(store, times(1)).readBootstrapPage(anyLong(),anyLong(),anyLong(),anyLong(),anyLong(),anyInt());
    }

    @Test void liveBacklogIsBoundedAndAllowsPartialCheckpoint() {
        service.refreshAt(now); // empty source is complete
        for (int id=1; id<=5; id++) add(id, now+id, 10d);
        service.refreshAt(now+10);
        assertThat(count()).isEqualTo(2);
        assertThat(service.loadStatus().phase()).isEqualTo("CATCHING_UP");
        assertThat(service.captureCheckpoint()).isNotNull();
        service.refreshAt(now+10);
        assertThat(count()).isEqualTo(4);
        service.refreshAt(now+10);
        assertThat(count()).isEqualTo(5);
        assertThat(service.loadStatus().complete()).isTrue();
    }

    @Test void futureRowsSurviveSeedAndAreReleasedOnceAndRetentionMovesDuringLoad() {
        add(1, now-48*3600000L, null);
        add(2, now+1000, 10d);
        service.refreshAt(now);
        assertThat(count()).isEqualTo(1); // the oldest retained sample is in the same count-based page
        BootstrapTestSupport.refreshFully(service, now+2000);
        assertThat(count()).isEqualTo(1);
        service.refreshAt(now+3000);
        assertThat(count()).isEqualTo(1);
        assertThat(service.availability(host,48).total().lost()).isZero();
    }

    @Test void failedPagePreservesNewRowsAndRetriesSamePositionWithoutDuplicates() {
        add(1, now-1, 10d); add(2, now-400000, null);
        service.refreshAt(now);
        add(3, now-100, 25d);
        clearInvocations(store);
        doThrow(new IllegalStateException("query failure")).when(store)
            .readBootstrapPage(anyLong(),anyLong(),anyLong(),eq(now-400000),anyLong(),anyInt());
        service.refreshAt(now);
        assertThat(count()).isEqualTo(3);
        service.refreshAt(now+500); // historical retry is delayed, new samples still flow
        verify(store, times(1)).readBootstrapPage(anyLong(),anyLong(),anyLong(),anyLong(),anyLong(),anyInt());
        doCallRealMethod().when(store).readBootstrapPage(anyLong(),anyLong(),anyLong(),anyLong(),anyLong(),anyInt());
        BootstrapTestSupport.refreshFully(service,now+1000);
        assertThat(count()).isEqualTo(3);
        verify(store, never()).readBootstrapPage(anyLong(),anyLong(),anyLong(),eq(Long.MAX_VALUE),anyLong(),anyInt());
    }

    @Test void processingFailureRollsBackExistingAndNewHostsAndPendingQueue() {
        add(1, now-1000, 10d); add(2, now-500, 20d);
        service.refreshAt(now);
        var bad = spy(new PingSample(4,host.id(),host.ip(),now-2,40d,null,20));
        doThrow(new IllegalStateException("processing failed")).when(bad).outcome();
        var newHost = new Host("other", "Other", "10.0.0.2", List.of());
        var partial = List.of(
            new PingSample(3,host.id(),host.ip(),now-3,30d,null,20),
            new PingSample(5,newHost.id(),newHost.ip(),now-3,30d,null,20),
            new PingSample(6,host.id(),host.ip(),now+500,60d,null,20), bad);
        doReturn(partial).when(store).readBootstrapPage(anyLong(),anyLong(),anyLong(),eq(now-1000),anyLong(),anyInt());
        service.refreshAt(now);
        assertThat(count()).isEqualTo(2);
        assertThat(service.availability(newHost,48).total().total()).isZero();
        assertThat(service.loadStatus().loadedFrom()).isEqualTo(now-1000+1);
        doCallRealMethod().when(store).readBootstrapPage(anyLong(),anyLong(),anyLong(),anyLong(),anyLong(),anyInt());
        BootstrapTestSupport.refreshFully(service, now+1000);
        assertThat(count()).isEqualTo(2); // staged future sample was not queued
        assertThat(service.current(List.of(host)).get(host.id()).stats().fiveMinutes()).isEqualTo(15d);
    }

    @Test void sqlKeysetHandlesIdenticalTimestampsAndExcludesNewIds() {
        for (int id=1; id<=5; id++) add(id,now-1,10d);
        var first = store.readBootstrapPage(4,now-300000,now,Long.MAX_VALUE,Long.MAX_VALUE,2);
        var second = store.readBootstrapPage(4,now-300000,now,first.getLast().measuredAt(),first.getLast().id(),2);
        assertThat(first).extracting(PingSample::id).containsExactly(4L,3L);
        assertThat(second).extracting(PingSample::id).containsExactly(2L,1L);
        assertThat(store.readBootstrapPage(4,now-300000,now,now-1,1,2)).isEmpty();
    }

    @Test void checkpointWriteIsScheduledForPartialHistory() {
        add(1,now-1,10d); add(2,now-1000,20d);
        var checkpoints = mock(AnalyticsCheckpointStore.class);
        when(checkpoints.epoch()).thenReturn("epoch");
        when(checkpoints.candidates()).thenReturn(List.of());
        service.configureCheckpoints(checkpoints,300000);
        service.refreshAt(now);
        verify(checkpoints,timeout(2000)).save(any());
        assertThat(service.loadStatus().complete()).isFalse();
    }
}
