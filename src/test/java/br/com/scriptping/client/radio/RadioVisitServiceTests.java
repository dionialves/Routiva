package br.com.scriptping.client.radio;

import br.com.scriptping.equipment.EquipmentProvider;
import br.com.scriptping.zabbix.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RadioVisitServiceTests {
    static final String MAC = "AA:BB:CC:DD:EE:FF";
    RadioClientService persistence;
    RadioVisitService visits;
    AtomicLong now;
    @BeforeEach void setup() {
        persistence = mock(RadioClientService.class); var inventory = mock(ZabbixService.class); var clock = mock(Clock.class);
        now = new AtomicLong(System.currentTimeMillis()); when(clock.millis()).thenAnswer(call -> now.get());
        when(inventory.hosts()).thenReturn(List.of(new Host("ap", "AP", "192.0.2.1", List.of())));
        visits = new RadioVisitService(persistence, inventory, 30000, clock);
    }
    @AfterEach void shutdown() { visits.shutdown(); }
    void read(String visit, String signal) {
        visits.observe(visit, "ap", "owner", new EquipmentProvider.Snapshot(now.get(), List.of(Map.of("mac-address", MAC, "signal", signal)), ""));
    }
    @Test void openingReadingAndLatencyUseOnlyMemoryUntilClose() {
        String id = visits.open("ap", "owner"); read(id, "-60");
        now.addAndGet(10000); read(id, "-70");
        visits.latency(id, "ap", "owner", MAC, now.get(), Map.of("startedAt", now.get() - 5000, "loss", 0d, "latency", 2d));
        assertThat(visits.active(id)).isTrue(); verifyNoInteractions(persistence);
        visits.close(id, "ap", "owner");
        var batch = org.mockito.ArgumentCaptor.forClass(RadioVisitService.Batch.class);
        verify(persistence, timeout(2000)).persistVisit(batch.capture());
        assertThat(batch.getValue().samples()).hasSize(2); assertThat(batch.getValue().tests()).hasSize(1);
        assertThat(batch.getValue().tests().getFirst().data().get("observationAt")).isEqualTo(now.get() - 10000);
    }
    @Test void slowPersistenceNeverBlocksCloseOrAnotherApsRead() throws Exception {
        var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(call -> { started.countDown(); assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); return null; }).when(persistence).persistVisit(any());
        String id = visits.open("ap", "owner"); read(id, "-60");
        try {
            visits.close(id, "ap", "owner"); assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            String other = visits.open("ap", "owner"); read(other, "-70");
            assertThat(visits.active(other)).isTrue(); assertThat(visits.active(id)).isFalse();
            visits.close(id, "ap", "owner"); verify(persistence).persistVisit(any());
        } finally { release.countDown(); }
    }
    @Test void snapshotsAreCopiedDeduplicatedAndErrorsDoNotCount() {
        String id = visits.open("ap", "owner"); var raw = new HashMap<>(Map.of("mac-address", MAC, "signal", "-60"));
        visits.observe(id, "ap", "owner", new EquipmentProvider.Snapshot(now.get(), List.of(raw), "")); raw.put("signal", "-1");
        read(id, "-5"); now.addAndGet(10000);
        visits.observe(id, "ap", "owner", new EquipmentProvider.Snapshot(now.get(), List.of(raw), "erro"));
        read(id, "-70"); now.addAndGet(10000);
        visits.observe(id, "ap", "owner", new EquipmentProvider.Snapshot(now.get(), List.of(), ""));
        visits.close(id, "ap", "owner"); var batch = org.mockito.ArgumentCaptor.forClass(RadioVisitService.Batch.class);
        verify(persistence, timeout(2000)).persistVisit(batch.capture());
        assertThat(batch.getValue().samples()).hasSize(2); assertThat(batch.getValue().samples().getFirst().raw().get("signal")).isEqualTo("-60");
        assertThat(batch.getValue().samples().getLast().at()).isEqualTo(now.get() - 10000);
    }
    @Test void expiryAndRepeatedCloseSubmitOnlyOneBatchAndEnforceOwner() {
        String id = visits.open("ap", "owner"); read(id, "-60");
        assertThatThrownBy(() -> visits.close(id, "ap", "intruder")).isInstanceOf(ResponseStatusException.class);
        now.addAndGet(31000); visits.expire(); visits.expire(); visits.close(id, "ap", "owner");
        verify(persistence, timeout(2000).times(1)).persistVisit(any()); assertThat(visits.active(id)).isFalse();
    }
    @Test void identityNamesAreCapturedInMemoryWithoutRewritingEarlierKnowledge() {
        String id = visits.open("ap", "owner"); read(id, "-60");
        visits.identities(id, "ap", "owner", Map.of(MAC, List.of("alice")), Map.of(MAC, "192.0.2.10"));
        now.addAndGet(10000); read(id, "-70"); visits.close(id, "ap", "owner");
        var batch = org.mockito.ArgumentCaptor.forClass(RadioVisitService.Batch.class); verify(persistence, timeout(2000)).persistVisit(batch.capture());
        assertThat(batch.getValue().samples().getFirst().observedLogins()).isEmpty();
        assertThat(batch.getValue().samples().getLast().observedLogins()).containsExactly("alice");
        assertThat(batch.getValue().samples().getLast().raw().get("ip-address")).isEqualTo("192.0.2.10");
    }
    @Test void failedBatchRemainsInMemoryAndIsRetriedWithoutReopeningTheVisit() {
        doThrow(new IllegalStateException("DB indisponível")).doNothing().when(persistence).persistVisit(any());
        visits.startWorker(); String id = visits.open("ap", "owner"); read(id, "-60"); visits.close(id, "ap", "owner");
        verify(persistence, timeout(2000)).persistVisit(any());
        // Retry deadlines use the injected clock; advance it after the worker records the failure.
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(4)).untilAsserted(() -> {
            now.addAndGet(31000); verify(persistence, times(2)).persistVisit(any());
        });
        assertThat(visits.active(id)).isFalse();
    }
    @Test void completedLatencyAfterCloseIsResubmittedForTheSameVisit() {
        visits.startWorker(); String id = visits.open("ap", "owner"); read(id, "-60");
        now.addAndGet(10000); read(id, "-70"); visits.close(id, "ap", "owner");
        verify(persistence, timeout(2000)).persistVisit(any());
        visits.latency(id, "ap", "owner", MAC, now.get(), Map.of("startedAt", now.get() - 5000, "loss", 100d));
        var batches = org.mockito.ArgumentCaptor.forClass(RadioVisitService.Batch.class);
        verify(persistence, timeout(3000).times(2)).persistVisit(batches.capture());
        assertThat(batches.getAllValues().getLast().id()).isEqualTo(id);
        assertThat(batches.getAllValues().getLast().tests()).hasSize(1);
    }

}
