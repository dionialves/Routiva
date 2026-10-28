package br.com.scriptping;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AvailabilityCacheTests {
    private final SampleStore store = mock(SampleStore.class);
    private final List<PingSample> rows = new ArrayList<>();
    private final Host host = new Host("h", "Host", "10.0.0.1", List.of());
    private final PacketLossAnalysisService engine = new PacketLossAnalysisService(store, new PacketLossPolicy(), 1000, 1500);
    private final HostAvailabilityService service = new HostAvailabilityService(engine);
    private final long now = 200_040_000L; // minute boundary

    AvailabilityCacheTests() {
        when(store.committedBoundary()).thenAnswer(i -> rows.stream().mapToLong(PingSample::id).max().orElse(0));
        feed();
    }
    private void feed() {
        doAnswer(i -> {
            long after = i.getArgument(0), end = i.getArgument(1), cutoff = i.getArgument(2);
            Consumer<PingSample> consumer = i.getArgument(3);
            rows.stream().filter(s -> s.id() > after && s.id() <= end && s.measuredAt() >= cutoff).forEach(consumer);
            return null;
        }).when(store).readIncrement(anyLong(), anyLong(), anyLong(), any());
    }
    private void add(long at, Double latency, String error) {
        rows.add(new PingSample(rows.size() + 1, host.id(), host.ip(), at, latency, error));
    }

    @Test
    void requestsOnlyReadPublishedReportsIncludingColdStartAndPeriodChanges() throws Exception {
        assertThat(service.report(host, 24).evaluatedAt()).isNull();
        verifyNoInteractions(store);
        IncrementalTestSupport.startBeforeArrivals(store, rows, engine, now);
        add(now - 1, 10d, null);
        IncrementalTestSupport.refreshFully(engine, now);
        var first = service.report(host, 24);
        add(now + 1, null, "Tempo esgotado");
        clearInvocations(store);
        var monitor = mock(MonitorService.class);
        when(monitor.hosts()).thenReturn(List.of(host));
        var mvc = MockMvcBuilders.standaloneSetup(new HostDetailsController(monitor, service)).build();
        for (int hours : HostAvailabilityService.PERIODS) {
            mvc.perform(get("/api/hosts/h/availability").param("hours", "" + hours))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total.healthy").value(1))
                .andExpect(jsonPath("$.bucketMillis").value(60000));
        }
        for (int hours : new int[] {2, 4, 12}) {
            mvc.perform(get("/api/hosts/h/availability").param("hours", "" + hours))
                .andExpect(status().isBadRequest());
        }
        assertThat(service.report(host, 24)).isSameAs(first);
        verifyNoInteractions(store);
        IncrementalTestSupport.refreshFully(engine, now + 10000);
        assertThat(service.report(host, 24).total().lost()).isEqualTo(1);
    }

    @Test
    void preservesSingleLossCompleteLossAndCollectionErrorsWithoutInventingLatency() {
        IncrementalTestSupport.startBeforeArrivals(store, rows, engine, now);
        for (int i = 0; i < 59; i++) add(now - 60000 + i * 1000, 10d, null);
        add(now - 1000, null, "Tempo esgotado");
        for (int i = 0; i < 60; i++) add(now - 120000 + i * 1000, null, "Sem resposta ICMP");
        add(now - 180000, null, "Falha ao executar ping");
        IncrementalTestSupport.refreshFully(engine, now);
        var report = service.report(host, 1);
        var last = report.series().getLast().metrics();
        assertThat(last.latency()).isEqualTo(10);
        assertThat(last.lost()).isEqualTo(1);
        assertThat(last.loss()).isCloseTo(100d / 60, within(1e-10));
        var outage = report.series().get(report.series().size() - 2).metrics();
        assertThat(outage.loss()).isEqualTo(100);
        assertThat(outage.latency()).isNull();
        assertThat(outage.technical()).isZero();
        var errors = report.series().get(report.series().size() - 3).metrics();
        assertThat(errors.errors()).isEqualTo(1);
        assertThat(errors.loss()).isNull();
        assertThat(report.total().loss()).isCloseTo(100d * 61 / 120, within(1e-10));
        assertThat(report.total().latency()).isEqualTo(10);
    }

    @Test
    void clipsExactEdgesCorrectsClosedMinutesAndExpiresWithoutNewRows() {
        IncrementalTestSupport.startBeforeArrivals(store, rows, engine, now);
        long reference = now + 12500;
        add(reference - 3600000 - 1, 1000d, null);
        add(reference - 3600000, 10d, null);
        add(reference - 1, 20d, null);
        add(reference, null, "Tempo esgotado");
        IncrementalTestSupport.refreshFully(engine, reference);
        var first = service.report(host, 1);
        assertThat(first.total().total()).isEqualTo(2);
        assertThat(first.total().latency()).isEqualTo(15);
        assertThat(first.series()).hasSize(61);
        assertThat(first.series().getFirst().partial()).isTrue();
        assertThat(first.series().getLast().provisional()).isTrue();
        assertThat(first.lastSampleAt()).isEqualTo(reference - 1);
        add(now - 60000, null, "Tempo esgotado"); // newly arrived closed-minute correction
        IncrementalTestSupport.refreshFully(engine, reference + 10000);
        var corrected = service.report(host, 1);
        assertThat(corrected.total().lost()).isEqualTo(2);
        assertThat(corrected.total().healthy()).isEqualTo(1);
        assertThat(first.total().lost()).isZero(); // immutable previous snapshot
        assertThat(corrected.series().stream().mapToLong(p -> p.metrics().total()).sum()).isEqualTo(corrected.total().total());
        IncrementalTestSupport.refreshFully(engine, reference + 3600001);
        assertThat(service.report(host, 1).total().total()).isZero();
    }

    @Test
    void failuresKeepAllPreviousDataAndRebuildWithoutDuplicates() {
        IncrementalTestSupport.startBeforeArrivals(store, rows, engine, now);
        add(now - 1, 10d, null);
        IncrementalTestSupport.refreshFully(engine, now);
        var before = service.report(host, 1);
        var beforeLatency = engine.current(List.of(host)).get(host.id());
        add(now + 1, null, "Tempo esgotado");
        doAnswer(i -> {
            Consumer<PingSample> consumer = i.getArgument(3);
            consumer.accept(rows.getLast());
            assertThat(service.report(host, 1)).isSameAs(before);
            throw new IllegalStateException("failure during streaming");
        }).when(store).readIncrement(anyLong(), anyLong(), anyLong(), any());
        assertThatThrownBy(() -> IncrementalTestSupport.refreshFully(engine, now + 10000)).isInstanceOf(IllegalStateException.class);
        assertThat(service.report(host, 1)).isSameAs(before);
        assertThat(engine.current(List.of(host)).get(host.id())).isSameAs(beforeLatency);
        feed();
        IncrementalTestSupport.refreshFully(engine, now + 20000);
        assertThat(service.report(host, 1).total().total()).isEqualTo(2);
        var restarted = new PacketLossAnalysisService(store, new PacketLossPolicy(), 1000, 1500);
        IncrementalTestSupport.refreshFully(restarted, now + 20000);
        assertThat(restarted.availability(host, 1).total().total()).isZero();
        assertThat(restarted.availability(host, 1).version()).isNotEqualTo(service.report(host, 1).version());
        IncrementalTestSupport.refreshFully(engine, now); // clock rewind starts from the current boundary
        assertThat(service.report(host, 1).total().total()).isZero();
    }

    @Test
    void includes48HoursWithoutGrowingLatencyHistoryAndRespectsReducedRetentionAndIp() {
        IncrementalTestSupport.startBeforeArrivals(store, rows, engine, now);
        add(now - 47 * 3600000L, 10d, null);
        add(now - 3600000L - 1, 30d, null);
        add(now - 1, 20d, null);
        add(now + 1, null, "Tempo esgotado");
        IncrementalTestSupport.refreshFully(engine, now);
        assertThat(service.report(host, 48).total().total()).isEqualTo(3);
        assertThat(engine.current(List.of(host)).get(host.id()).stats().attemptsEightHours()).isEqualTo(2);
        assertThat(service.report(new Host(host.id(), "Host", "10.0.0.2", List.of()), 48).total().total()).isZero();
        var limited = new PacketLossAnalysisService(store, new PacketLossPolicy(), 1000, 1500, 1);
        IncrementalTestSupport.startBeforeArrivals(store, rows, limited, now);
        IncrementalTestSupport.refreshFully(limited, now);
        assertThat(limited.availability(host, 48).total().total()).isEqualTo(1);
        assertThat(limited.availability(host, 48).retainedFrom()).isEqualTo(now - 3600000L);
        IncrementalTestSupport.refreshFully(limited, now + 10000);
        assertThat(limited.availability(host, 48).total().lost()).isEqualTo(1);
    }

    @Test
    void matchesRawReferenceAcrossAllPeriodsWithLateSamplesAndThresholdChanges() {
        IncrementalTestSupport.startBeforeArrivals(store, rows, engine, now);
        var random = new Random(19);
        for (int i = 0; i < 3000; i++) {
            long at = now - random.nextLong(49 * 3600000L);
            Double latency = i % 5 == 0 ? null : random.nextDouble(100);
            rows.add(new PingSample(i + 1, host.id(), host.ip(), at, latency,
                i % 10 == 0 ? "internal error" : "Tempo esgotado", i % 2 == 0 ? 20 : 80));
        }
        IncrementalTestSupport.refreshFully(engine, now);
        for (long reference : new long[] { now, now + 10000, now + 3600000 }) {
            IncrementalTestSupport.refreshFully(engine, reference);
            for (int hours : HostAvailabilityService.PERIODS) {
                var expected = new HostAvailabilityService.Counts();
                rows.stream().filter(s -> s.measuredAt() >= reference - hours * 3600000L && s.measuredAt() < reference)
                    .forEach(s -> expected.add(s.outcome(), s.latency(), s.latencyLimit()));
                var actual = service.report(host, hours);
                assertThat(actual.total().healthy()).isEqualTo(expected.h);
                assertThat(actual.total().degraded()).isEqualTo(expected.d);
                assertThat(actual.total().lost()).isEqualTo(expected.p);
                assertThat(actual.total().errors()).isEqualTo(expected.e);
                if (expected.h + expected.d > 0)
                    assertThat(actual.total().latency()).isCloseTo(expected.metrics().latency(), within(1e-9));
                assertThat(actual.series().stream().mapToLong(p -> p.metrics().total()).sum()).isEqualTo(actual.total().total());
            }
        }
    }
}
