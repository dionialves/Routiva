package br.com.scriptping.accesspoint;

import br.com.scriptping.equipment.EquipmentProvider;
import br.com.scriptping.ingestion.*;
import br.com.scriptping.ixc.IxcClient;
import br.com.scriptping.zabbix.Host;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LatencyAssessmentTests {
    @Test void measuresApRetainsClientsWithoutIpAndIsolatesOwners() {
        var aps = mock(AccessPointService.class);
        var ixc = mock(IxcClient.class);
        var collector = mock(CollectorClient.class);
        var pings = new ClientPingSessions();
        when(collector.enabled()).thenReturn(true);
        when(aps.host("42")).thenReturn(new Host("42", "AP", "192.0.2.1", List.of()));
        when(aps.read("42", "wireless", null)).thenReturn(new EquipmentProvider.Snapshot(1,
            List.of(Map.of("mac-address", "AA:BB:CC:DD:EE:FF")), null));
        when(ixc.findByMacs(anyCollection())).thenReturn(List.of());
        var controller = new LatencyAssessmentController(aps, ixc, pings, collector);
        var owner = new MockHttpSession();
        var view = (LatencyAssessmentService.View) controller.start("42", owner).getBody();
        assertThat(view.packetCount()).isEqualTo(60);
        assertThat(view.completed()).isFalse();
        assertThat(view.results()).hasSize(2);
        assertThat(view.results().get(1).target().warning()).contains("Sem IPv4");
        assertThatThrownBy(() -> controller.start("42", owner)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.read("42", view.id(), new MockHttpSession()))
            .isInstanceOf(ResponseStatusException.class);
        controller.stop("42", view.id(), owner);
        assertThatThrownBy(() -> controller.read("42", view.id(), owner)).isInstanceOf(ResponseStatusException.class);
        verify(collector, times(2)).refreshClientPings();
    }
    @Test void finalResponseUsesServerTimeAndKeepsOneSnapshotUntilExpiry() {
        var aps = mock(AccessPointService.class);
        var collector = mock(CollectorClient.class);
        var pings = mock(ClientPingSessions.class);
        var clock = mock(java.time.Clock.class);
        var now = new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());
        when(clock.millis()).thenAnswer(call -> now.get());
        when(collector.enabled()).thenReturn(true);
        when(aps.host("42")).thenReturn(new Host("42", "AP", "192.0.2.1", List.of()));
        when(aps.read("42", "wireless", null)).thenReturn(new EquipmentProvider.Snapshot(1, List.of(), null));
        var owner = new MockHttpSession();
        var first = new ClientPingSessions.View("probe", "192.0.2.1", now.get(), List.of());
        when(pings.startPackets(anyString(), anyString(), anyString(), anyInt(), anyLong())).thenReturn(first);
        when(pings.read("probe", "42", owner.getId())).thenReturn(first);
        var controller = new LatencyAssessmentController(aps, mock(IxcClient.class), pings, collector, clock);
        var started = (LatencyAssessmentService.View) controller.start("42", 5, owner).getBody();
        now.addAndGet(7000);
        var sample = new ClientPingSessions.Point(started.startedAt(), 2d, 0d, "SUCCESS");
        var finalProbe = new ClientPingSessions.View("probe", "192.0.2.1", first.startedAt(), java.util.Collections.nCopies(5, sample));
        when(pings.read("probe", "42", owner.getId())).thenReturn(finalProbe);
        var response = controller.read("42", started.id(), owner);
        assertThat(response.getHeaders().getDate()).isEqualTo(now.get() / 1000 * 1000);
        var finalView = (LatencyAssessmentService.View) response.getBody();
        assertThat(finalView.results().getFirst().samples()).hasSize(5);
        assertThat(finalView.completed()).isTrue();
        assertThat(finalView.packetCount()).isEqualTo(5);
        when(pings.read("probe", "42", owner.getId())).thenThrow(new IllegalStateException("late read"));
        assertThat(controller.read("42", started.id(), owner).getBody()).isSameAs(finalView);
        now.set(finalView.endsAt() + 300001);
        controller.cleanup();
        assertThatThrownBy(() -> controller.read("42", started.id(), owner)).isInstanceOf(ResponseStatusException.class);
    }
    @Test void incompletePacketRunIsAnErrorAtSafetyDeadline() {
        var aps = mock(AccessPointService.class);
        var collector = mock(CollectorClient.class);
        var clock = mock(java.time.Clock.class);
        var now = new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());
        when(clock.millis()).thenAnswer(call -> now.get());
        when(collector.enabled()).thenReturn(true);
        when(aps.host("42")).thenReturn(new Host("42", "AP", "192.0.2.1", List.of()));
        when(aps.read("42", "wireless", null)).thenReturn(new EquipmentProvider.Snapshot(1, List.of(), null));
        var controller = new LatencyAssessmentController(aps, mock(IxcClient.class), new ClientPingSessions(), collector, clock);
        var owner = new MockHttpSession();
        var started = (LatencyAssessmentService.View) controller.start("42", 5, owner).getBody();
        now.set(started.endsAt());
        var finalView = (LatencyAssessmentService.View) controller.read("42", started.id(), owner).getBody();
        assertThat(finalView.failed()).isTrue();
        assertThat(finalView.completed()).isFalse();
    }
    @Test void rejectsUnsupportedPacketCounts() {
        var aps = mock(AccessPointService.class);
        var controller = new LatencyAssessmentController(aps, mock(IxcClient.class),
            new ClientPingSessions(), mock(CollectorClient.class));
        for (int count : List.of(0, 1, 6, 100))
            assertThatThrownBy(() -> controller.start("42", count, new MockHttpSession()))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(aps);
    }
    @Test void taskReservationBlocksBrowserAndIsReleasedWithoutHttpSession() {
        var aps = mock(AccessPointService.class);
        var collector = mock(CollectorClient.class); when(collector.enabled()).thenReturn(true);
        var service = new LatencyAssessmentService(aps, mock(IxcClient.class), new ClientPingSessions(), collector);
        service.reserve("42", "task:run");
        assertThatThrownBy(() -> service.start("42", 30, "browser-owner"))
            .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(409));
        verifyNoInteractions(aps);
        service.release("42", "task:run");
        service.reserve("42", "another-owner"); service.release("42", "another-owner");
    }
    @Test void refusesDisabledCollectorBeforeQueryingEquipment() {
        var aps = mock(AccessPointService.class);
        var controller = new LatencyAssessmentController(aps, mock(IxcClient.class),
            new ClientPingSessions(), mock(CollectorClient.class));
        assertThatThrownBy(() -> controller.start("42", new MockHttpSession()))
            .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(aps);
    }
}
