package br.com.scriptping.accesspoint;


import br.com.scriptping.equipment.EquipmentProvider;
import br.com.scriptping.ingestion.*;
import br.com.scriptping.ixc.IxcClient;
import br.com.scriptping.zabbix.Host;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RadioLatencyCaptureTests {
    @ParameterizedTest @ValueSource(ints = {-1, 0, 2, 5})
    void capturesCompletedAssessmentWithoutBrowserPollingAndUsesOnlyIcmpResults(int losses) throws Exception {
        String mac = "AA:BB:CC:DD:EE:FF";
        var aps = mock(AccessPointService.class); var ixc = mock(IxcClient.class);
        var pings = mock(ClientPingSessions.class); var collector = mock(CollectorClient.class);
        var ready = new AtomicBoolean(false); var counter = new AtomicInteger();
        when(collector.enabled()).thenReturn(true);
        when(aps.host("ap")).thenReturn(new Host("ap", "AP", "192.0.2.1", List.of()));
        when(aps.read("ap", "wireless", null)).thenReturn(new EquipmentProvider.Snapshot(System.currentTimeMillis(), List.of(Map.of("mac-address", mac)), null));
        var constructor = IxcClient.Login.class.getDeclaredConstructor(com.fasterxml.jackson.databind.JsonNode.class, String.class);
        constructor.setAccessible(true);
        var login = constructor.newInstance(new ObjectMapper().readTree("{\"login\":\"alice\",\"ip\":\"192.0.2.10\"}"), mac);
        when(ixc.findByMacs(anyCollection())).thenReturn(List.of(login));
        when(pings.startPackets(anyString(), anyString(), anyString(), anyInt(), anyLong())).thenAnswer(call ->
            new ClientPingSessions.View("probe" + counter.incrementAndGet(), call.getArgument(2), 0, List.of()));
        when(pings.read(anyString(), anyString(), anyString())).thenAnswer(call -> {
            var samples = new ArrayList<ClientPingSessions.Point>();
            if (ready.get()) for (int i = 0; i < 5; i++) {
                boolean lost = losses == -1 ? i == 1 : i < losses;
                if (losses == -1 && i >= 2) {
                    samples.add(new ClientPingSessions.Point(i, null, null, "SEND_ERROR")); continue;
                }
                samples.add(new ClientPingSessions.Point(i, lost ? null : 4d, lost ? 100d : 0d, lost ? "TIMEOUT" : "SUCCESS"));
            }
            return new ClientPingSessions.View(call.getArgument(0), "192.0.2.10", 0, samples);
        });
        var service = new LatencyAssessmentService(aps, ixc, pings, collector);
        var started = service.start("ap", 5, "task:execution");
        ready.set(true); service.cleanup(); service.cleanup();
        var results = service.measurements(service.read("ap", started.id(), "task:execution"));
        var stats = results.getFirst().data();
        assertThat(stats.get("loss")).isEqualTo(losses == -1 ? 50d : losses * 20d);
        assertThat(stats.get("replies")).isEqualTo(losses == -1 ? 1 : 5 - losses);
        assertThat(stats.get("latency")).isEqualTo(losses == 5 ? null : 4d);
    }
}
