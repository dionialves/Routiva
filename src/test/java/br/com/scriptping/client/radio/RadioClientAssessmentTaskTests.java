package br.com.scriptping.client.radio;

import br.com.scriptping.accesspoint.*;
import br.com.scriptping.equipment.EquipmentProvider;
import br.com.scriptping.ingestion.ClientPingSessions;
import br.com.scriptping.ixc.IxcClient;
import br.com.scriptping.scheduling.ScheduledTask;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RadioClientAssessmentTaskTests {
    static final String MAC = "AA:BB:CC:DD:EE:FF";
    AccessPointService aps;
    LatencyAssessmentService latency;
    RadioClientService clients;
    AtomicLong now;
    Clock clock;
    RadioClientAssessmentTask task;
    List<ScheduledTask.Detail> details;
    ScheduledTask.Context context;
    @BeforeEach void setup() throws Exception {
        aps = mock(AccessPointService.class); latency = mock(LatencyAssessmentService.class); clients = mock(RadioClientService.class);
        now = new AtomicLong(100000); clock = mock(Clock.class); when(clock.millis()).thenAnswer(c -> now.get());
        task = new RadioClientAssessmentTask(aps, latency, clients, 2, 10000, clock, millis -> now.addAndGet(millis));
        details = new ArrayList<>(); context = new ScheduledTask.Context("execution", details::add);
        when(latency.ready()).thenReturn(true);
        when(aps.list()).thenReturn(List.of(ap("one", true), ap("two", true)));
        when(latency.prepare(anyString())).thenAnswer(c -> prepared(c.getArgument(0), true));
        when(aps.read(anyString(), eq("wireless"), isNull())).thenAnswer(c -> snapshot());
        when(latency.startPrepared(any(), eq(30), eq("task:execution"))).thenAnswer(c -> view(c.getArgument(0), false));
        when(latency.read(anyString(), anyString(), eq("task:execution"))).thenAnswer(c -> view(prepared(c.getArgument(0), true), true));
        when(latency.measurements(any())).thenCallRealMethod();
    }
    AccessPointService.Row ap(String id, boolean available) {
        return new AccessPointService.Row(id, "AP " + id, "192.0.2.1", "MikroTik", "", 1, null, available, List.of());
    }
    EquipmentProvider.Snapshot snapshot() { return new EquipmentProvider.Snapshot(now.get(), List.of(Map.of("mac-address", MAC, "signal", "-60")), null); }
    IxcClient.Login login() throws Exception {
        var ctor = IxcClient.Login.class.getDeclaredConstructor(com.fasterxml.jackson.databind.JsonNode.class, String.class);
        ctor.setAccessible(true);
        return ctor.newInstance(new ObjectMapper().readTree("{\"login\":\"alice\",\"ip\":\"192.0.2.10\"}"), MAC);
    }
    LatencyAssessmentService.Prepared prepared(String host, boolean ixcAvailable) throws Exception {
        return new LatencyAssessmentService.Prepared(host, snapshot(), ixcAvailable ? List.of(login()) : List.of(), List.of(
            new LatencyAssessmentService.Target("AP", "", "192.0.2.1", null),
            new LatencyAssessmentService.Target("alice", MAC, ixcAvailable ? "192.0.2.10" : "", ixcAvailable ? null : "IXC indisponível")), ixcAvailable);
    }
    LatencyAssessmentService.View view(LatencyAssessmentService.Prepared prepared, boolean complete) {
        var samples = complete ? Collections.nCopies(30, new ClientPingSessions.Point(now.get(), 2d, 0d, "SUCCESS")) : List.<ClientPingSessions.Point>of();
        return new LatencyAssessmentService.View(prepared.host(), 100000, now.get() + (complete ? 0 : 60000),
            prepared.targets().stream().map(target -> new LatencyAssessmentService.Result(target, samples)).toList(), 30, complete, false);
    }
    @Test void evaluatesSequentiallyWithThirtyPacketsThenPersistsOneBatchPerAp() throws Exception {
        var result = task.execute(Map.of(), context);
        assertThat(result.partial()).isFalse();
        var batches = org.mockito.ArgumentCaptor.forClass(RadioCollection.Batch.class);
        verify(clients, times(2)).persistCollection(batches.capture(), anyList());
        for (var batch : batches.getAllValues()) {
            assertThat(batch.samples()).hasSize(4);
            assertThat(batch.samples().getLast().at() - batch.samples().getFirst().at()).isEqualTo(30000);
            assertThat(batch.tests()).singleElement().satisfies(t -> {
                assertThat(t.data().get("total")).isEqualTo(30); assertThat(t.data().get("latency")).isEqualTo(2d);
            });
            assertThat(batch.samples().getFirst().observedLogins()).containsExactly("alice");
            assertThat(batch.samples().getFirst().raw()).containsEntry("ip-address", "192.0.2.10");
        }
        var ordered = inOrder(latency, clients);
        ordered.verify(latency).reserve("one", "task:execution");
        ordered.verify(clients).persistCollection(argThat(b -> b.host().equals("one")), anyList());
        ordered.verify(latency).stop("one", "one", "task:execution");
        ordered.verify(latency).release("one", "task:execution");
        ordered.verify(latency).reserve("two", "task:execution");
        assertThat(details.stream().filter(d -> d.state().equals("COMPLETED"))).hasSize(2);
    }
    @Test void failedApDoesNotStopRemainingInventory() throws Exception {
        when(latency.prepare("one")).thenThrow(new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT));
        assertThat(task.execute(Map.of(), context).partial()).isTrue();
        verify(clients).persistCollection(argThat(b -> b.host().equals("two")), anyList());
        verify(latency).release("one", "task:execution");
        assertThat(details).anyMatch(d -> d.hostId().equals("one") && d.state().equals("FAILED"));
    }
    @Test void unavailableIxcPreservesObservationsButReportsMissingLatency() throws Exception {
        when(aps.list()).thenReturn(List.of(ap("one", true)));
        when(latency.prepare("one")).thenAnswer(c -> prepared("one", false));
        when(latency.read(anyString(), anyString(), anyString())).thenAnswer(c -> view(prepared("one", false), true));
        assertThat(task.execute(Map.of(), context).partial()).isTrue();
        var batch = org.mockito.ArgumentCaptor.forClass(RadioCollection.Batch.class);
        verify(clients).persistCollection(batch.capture(), eq(List.of()));
        assertThat(batch.getValue().samples().getFirst().observedLogins()).isEmpty();
        assertThat(batch.getValue().tests()).isEmpty();
        assertThat(details.getLast().message()).contains("IXC indisponível");
    }
    @Test void interruptionStopsProbesReleasesApAndDoesNotPersistIncompleteBatch() throws Exception {
        var interrupted = new RadioClientAssessmentTask(aps, latency, clients, 2, 10000, clock,
            millis -> { throw new InterruptedException(); });
        assertThatThrownBy(() -> interrupted.execute(Map.of(), context)).isInstanceOf(InterruptedException.class);
        verify(latency).stop("one", "one", "task:execution"); verify(latency).release("one", "task:execution");
        verify(latency, never()).reserve("two", "task:execution"); verifyNoInteractions(clients);
        assertThat(details.getLast().state()).isEqualTo("INTERRUPTED");
    }
    @Test void unavailableCollectorStopsBeforeQueryingInventory() {
        when(latency.ready()).thenReturn(false);
        assertThatThrownBy(() -> task.execute(Map.of(), context)).isInstanceOf(IllegalStateException.class);
        verify(aps, never()).list(); verifyNoInteractions(clients);
    }
    @Test void failureToPersistIsReportedAndDoesNotRepeatMeasurements() throws Exception {
        doThrow(new IllegalStateException("database offline")).when(clients).persistCollection(any(), anyList());
        assertThatThrownBy(() -> task.execute(Map.of(), context)).isInstanceOf(IllegalStateException.class);
        verify(latency, times(2)).startPrepared(any(), eq(30), anyString());
        verify(latency).release("one", "task:execution"); verify(latency).release("two", "task:execution");
        assertThat(details.stream().filter(d -> d.state().equals("FAILED"))).hasSize(2);
    }
    @Test void staleSnapshotsCannotManufactureHistoricalCoverage() throws Exception {
        when(aps.list()).thenReturn(List.of(ap("one", true)));
        var stale = snapshot(); when(aps.read(anyString(), eq("wireless"), isNull())).thenReturn(stale);
        assertThat(task.execute(Map.of(), context).partial()).isTrue();
        var batch = org.mockito.ArgumentCaptor.forClass(RadioCollection.Batch.class);
        verify(clients).persistCollection(batch.capture(), anyList());
        assertThat(batch.getValue().samples()).hasSize(1);
    }
    @Test void unavailableAndBusyApsAreReportedWithoutChangingClientRecords() {
        when(aps.list()).thenReturn(List.of(ap("one", false), ap("two", true)));
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT)).when(latency).reserve("two", "task:execution");
        assertThatThrownBy(() -> task.execute(Map.of(), context)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(clients);
        assertThat(details.getLast().message()).contains("ocupado");
    }
}
