package br.com.scriptping;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;

class AnalyticsCheckpointTests {
    final long now = 200_000_023L;
    final Host host = new Host("h", "Host", "10.0.0.1", List.of());
    final SampleStore samples = mock(SampleStore.class);
    final List<PingSample> rows = new ArrayList<>();
    final List<PacketLossAnalysisService> services = new ArrayList<>();
    JdbcTemplate jdbc;
    AnalyticsCheckpointStore checkpoints;

    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        jdbc = new JdbcTemplate(ds);
        checkpoints = new AnalyticsCheckpointStore(jdbc, new DataSourceTransactionManager(ds));
        when(samples.committedBoundary()).thenAnswer(i -> rows.stream().mapToLong(PingSample::id).max().orElse(0));
        doAnswer(i -> {
            long after=i.getArgument(0), through=i.getArgument(1), cutoff=i.getArgument(2);
            Consumer<PingSample> consumer=i.getArgument(3);
            rows.stream().filter(r -> r.id()>after && r.id()<=through && r.measuredAt()>=cutoff).forEach(consumer);
            return null;
        }).when(samples).readIncrement(anyLong(),anyLong(),anyLong(),any());
    }
    @AfterEach void close() { services.forEach(PacketLossAnalysisService::closeCheckpointWriter); }
    PacketLossAnalysisService service(boolean persistence) {
        var service = new PacketLossAnalysisService(samples, new PacketLossPolicy(), 1000, 2000);
        if (persistence) service.configureCheckpoints(checkpoints, 300000);
        if (!persistence) IncrementalTestSupport.startBeforeArrivals(samples, rows, service, now);
        services.add(service); return service;
    }
    void add(long id, long time, Double latency, String error, double limit) {
        rows.add(new PingSample(id,host.id(),host.ip(),time,latency,error,limit));
    }
    AnalyticsCheckpointStore.Capture capture(PacketLossAnalysisService service) {
        var capture = service.captureCheckpoint(); var h = capture.header();
        return new AnalyticsCheckpointStore.Capture(new AnalyticsCheckpointStore.Header(h.id(),h.reference(),h.cursor(),h.compatibility(),checkpoints.epoch(),h.parts(),h.manifest()), capture.parts());
    }
    @Test void legacyPartialCheckpointKeepsAggregatesButDropsHistoricalPosition() {
        add(1, now-1000, 10d, null, 20);
        var live = service(false);
        IncrementalTestSupport.refreshFully(live, now);
        var saved = capture(live);
        var parts = new ArrayList<>(saved.parts());
        parts.add(AnalyticsCheckpointStore.Part.of("bootstrap", "", "", 0,
            () -> CheckpointCodec.encode(out -> {
                out.writeLong(1); out.writeLong(now-1000); out.writeLong(1);
            })));
        var h = saved.header();
        checkpoints.save(new AnalyticsCheckpointStore.Capture(new AnalyticsCheckpointStore.Header(
            h.id(), h.reference(), h.cursor(), h.compatibility(), h.epoch(), parts.size(), ""), parts));
        add(2, now-500, 20d, null, 20);
        clearInvocations(samples);
        var restored = service(true);
        IncrementalTestSupport.refreshFully(restored, now);
        verify(samples).readIncrement(eq(1L), eq(2L), anyLong(), any());
        assertThat(restored.availability(host, 48).total().total()).isEqualTo(2);
        assertThat(restored.loadStatus().complete()).isTrue();
        assertThat(restored.captureCheckpoint().parts()).noneMatch(p -> p.kind().equals("bootstrap"));
    }

    @Test void checkpointDuringLiveCatchupRestoresCursorWithoutStartingHistoricalLoad() {
        var live = service(false); live.configureIncremental(2, 1000);
        live.refreshAt(now);
        for (int id=1; id<=5; id++) add(id, now-1000+id, 10d, null, 20);
        live.refreshAt(now);
        assertThat(live.loadStatus().phase()).isEqualTo("CATCHING_UP");
        checkpoints.save(capture(live));
        clearInvocations(samples);
        var restarted = service(true); restarted.configureIncremental(2, 1000);
        IncrementalTestSupport.refreshFully(restarted, now);
        assertThat(restarted.availability(host, 48).total().total()).isEqualTo(5);
    }

    @Test void restartPreservesAllPeriodsFutureSamplesLateArrivalsAndExactEdges() {
        add(1,now-47*3600000L,5d,null,3);
        add(2,now-3600000L,10d,null,20);
        add(3,now-1000,null,"Tempo esgotado",20);
        add(4,now,40d,null,30);
        add(5,now+5000,15d,null,10);
        var live=service(false); IncrementalTestSupport.refreshFully(live, now);
        checkpoints.save(capture(live));
        add(6,now-2000,8d,null,5); // late arrival, ID after checkpoint
        add(7,now+9000,null,"internal",20);
        IncrementalTestSupport.refreshFully(live, now+10000);
        clearInvocations(samples);
        var restarted=service(true); IncrementalTestSupport.refreshFully(restarted, now+10000);
        verify(samples).readIncrement(eq(5L),eq(7L),anyLong(),any());
        assertThat(restarted.current(List.of(host))).isEqualTo(live.current(List.of(host)));
        for (int period : HostAvailabilityService.PERIODS) {
            var actual = restarted.availability(host,period); var expected=live.availability(host,period);
            assertThat(actual.total()).isEqualTo(expected.total());
            assertThat(actual.series()).isEqualTo(expected.series());
            assertThat(actual.lastSampleAt()).isEqualTo(expected.lastSampleAt());
        }
    }
    @Test void capturedStateIsImmutableWhileLiveBlocksSplitExpireAndMinutesChange() {
        for (int i=1;i<=600;i++) add(i,now-600000+i*1000,10d,null,20);
        var live=service(false); IncrementalTestSupport.refreshFully(live, now);
        var captured=capture(live);
        add(601,now-500,99d,null,20);
        IncrementalTestSupport.refreshFully(live, now+1000); // mutate a frozen indicator block before pruning
        IncrementalTestSupport.refreshFully(live, now+9*3600000L); // prune indicator blocks and mutate availability
        checkpoints.save(captured); // serialization occurs after live mutation
        var restored=service(true); IncrementalTestSupport.refreshFully(restored, now+10000);
        var baseline=service(false); IncrementalTestSupport.refreshFully(baseline, now+10000);
        assertThat(restored.current(List.of(host))).isEqualTo(baseline.current(List.of(host)));
        assertThat(restored.availability(host,48).series()).isEqualTo(baseline.availability(host,48).series());
    }
    @Test void keepsTwoGenerationsReusesBlocksAndRollsBackIncompleteWrites() {
        add(1,now-1000,10d,null,20);
        var live=service(false); IncrementalTestSupport.refreshFully(live, now);
        var first=capture(live); checkpoints.save(first);
        int blocks=jdbc.queryForObject("SELECT COUNT(*) FROM analytics_checkpoint_block",Integer.class);
        checkpoints.save(capture(live)); checkpoints.save(capture(live));
        assertThat(checkpoints.candidates()).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM analytics_checkpoint_block",Integer.class)).isEqualTo(blocks);
        var bad=capture(live); var h=bad.header();
        var invalid = new AnalyticsCheckpointStore.Header(h.id(),h.reference(),h.cursor(),"x".repeat(100),h.epoch(),h.parts(),h.manifest());
        assertThatThrownBy(() -> checkpoints.save(new AnalyticsCheckpointStore.Capture(invalid,bad.parts()))).isInstanceOf(RuntimeException.class);
        assertThat(checkpoints.candidates()).hasSize(2);
    }
    @Test void corruptedNewestFallsBackToPreviousGeneration() {
        add(1,now-1000,10d,null,20);
        var live=service(false); IncrementalTestSupport.refreshFully(live, now); checkpoints.save(capture(live));
        add(2,now+1000,20d,null,20); IncrementalTestSupport.refreshFully(live, now+2000);
        var latest=capture(live); checkpoints.save(latest);
        jdbc.update("UPDATE analytics_checkpoint SET manifest='invalid' WHERE id=?",latest.header().id());
        clearInvocations(samples);
        var restored=service(true); IncrementalTestSupport.refreshFully(restored, now+3000);
        verify(samples).readIncrement(eq(1L),eq(2L),anyLong(),any());
        IncrementalTestSupport.refreshFully(live, now+3000);
        assertThat(restored.current(List.of(host))).isEqualTo(live.current(List.of(host)));
    }
    @Test void noNewSamplesStillExpiresWindowsAndPreservesLastError() {
        add(1,now-299000,null,"Sem resposta ICMP",20);
        var live=service(false); IncrementalTestSupport.refreshFully(live, now); checkpoints.save(capture(live));
        clearInvocations(samples);
        var restarted=service(true); IncrementalTestSupport.refreshFully(restarted, now+10000);
        verify(samples, never()).readIncrement(anyLong(),anyLong(),anyLong(),any());
        IncrementalTestSupport.refreshFully(live, now+10000);
        assertThat(restarted.current(List.of(host))).isEqualTo(live.current(List.of(host)));
        assertThat(restarted.availability(host,1).series()).isEqualTo(live.availability(host,1).series());
    }
    @Test void databaseFailureDuringRestoreRetriesWithoutStartingFullLoad() {
        add(1,now-1000,10d,null,20);
        var broken = mock(AnalyticsCheckpointStore.class);
        when(broken.epoch()).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("offline"));
        var restarted=new PacketLossAnalysisService(samples, new PacketLossPolicy(), 1000, 2000); services.add(restarted); restarted.configureCheckpoints(broken,300000);
        assertThatThrownBy(() -> IncrementalTestSupport.refreshFully(restarted, now)).isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
        assertThatThrownBy(() -> IncrementalTestSupport.refreshFully(restarted, now+10000)).isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
        verify(samples, never()).readIncrement(anyLong(),anyLong(),anyLong(),any());
        verify(broken,times(2)).epoch();
    }
    @Test void asyncWriteFailureDoesNotResetLiveStateOrRetryEveryCycle() throws Exception {
        add(1,now-1000,10d,null,20);
        var failing = mock(AnalyticsCheckpointStore.class);
        when(failing.epoch()).thenReturn("epoch"); when(failing.candidates()).thenReturn(List.of());
        var written = new java.util.concurrent.CountDownLatch(1);
        doAnswer(i -> { written.countDown(); throw new IllegalStateException("disk full"); }).when(failing).save(any());
        var live=service(false); live.configureCheckpoints(failing,300000); IncrementalTestSupport.refreshFully(live, now);
        assertThat(written.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        add(2,now+1000,20d,null,20); IncrementalTestSupport.refreshFully(live, now+10000);
        verify(samples).readIncrement(eq(1L),eq(2L),anyLong(),any());
        verify(failing,times(1)).save(any());
        assertThat(live.current(List.of(host)).get(host.id()).stats().attemptsFiveMinutes()).isEqualTo(2);
    }
    @Test void incompatibleConfigurationStartsAtCurrentBoundary() {
        add(1,now-1000,10d,null,20);
        var live=service(false); IncrementalTestSupport.refreshFully(live, now); checkpoints.save(capture(live));
        var restored=new PacketLossAnalysisService(samples,new PacketLossPolicy(),2000,2000);
        services.add(restored); restored.configureCheckpoints(checkpoints,300000);
        clearInvocations(samples); IncrementalTestSupport.refreshFully(restored, now+1000);
        verify(samples, never()).readIncrement(anyLong(),anyLong(),anyLong(),any());
        assertThat(restored.availability(host, 48).total().total()).isZero();
    }
}
