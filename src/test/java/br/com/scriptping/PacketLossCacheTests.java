package br.com.scriptping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class PacketLossCacheTests {

    @Test
    void repeatedDashboardReadsReuseRestoredSamplesWithoutFurtherSqlQueries() {
        SampleStore store = mock(SampleStore.class);
        long now = System.currentTimeMillis();
        Host host = new Host("cached", "Cached", "10.7.1.1", List.of());
        doAnswer(invocation -> {
            Consumer<PingSample> consumer = invocation.getArgument(1);
            for (int i = 1; i <= 3600; i++) consumer.accept(
                new PingSample(i, host.id(), host.ip(), now - 3600000 + i * 1000L, 10d, null)
            );
            return null;
        })
            .when(store)
            .readRecent(anyLong(), any());
        var service = new PacketLossAnalysisService(store, new PacketLossPolicy(), 1000, 2000);
        service.restore();
        var first = service.analyze(List.of(host), now).get(host.id());
        assertThat(service.analyze(List.of(host), now + 500).get(host.id())).isSameAs(first);
        service.analyze(List.of(host), now + 2000);
        verify(store, times(1)).readRecent(anyLong(), any());
        verifyNoMoreInteractions(store);
    }
}
