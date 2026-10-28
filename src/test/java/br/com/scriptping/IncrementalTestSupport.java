package br.com.scriptping;

import static org.mockito.Mockito.*;
import java.util.*;

final class IncrementalTestSupport {
    // Simulate arrivals after startup for calculation fixtures populated in advance.
    static void startBeforeArrivals(SampleStore store, List<PingSample> rows,
        PacketLossAnalysisService service, long now) {
        doReturn(0L).when(store).committedBoundary();
        service.refreshAt(now);
        doAnswer(i -> rows.stream().mapToLong(PingSample::id).max().orElse(0)).when(store).committedBoundary();
    }
    static void refreshFully(PacketLossAnalysisService service, long now) {
        for (int i=0; i<10000; i++) {
            service.refreshAt(now);
            if (service.loadStatus().complete()) return;
        }
        throw new AssertionError("Incremental catchup did not complete");
    }
}
