package br.com.scriptping;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.*;

/** Existing calculation tests explicitly wait for complete coverage; progressive tests do not. */
final class BootstrapTestSupport {
    static void pages(SampleStore store, List<PingSample> rows) {
        when(store.readBootstrapPage(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyInt()))
            .thenAnswer(i -> {
                long boundary=i.getArgument(0), from=i.getArgument(1), to=i.getArgument(2),
                    time=i.getArgument(3), id=i.getArgument(4);
                int size=i.getArgument(5);
                return rows.stream().filter(s -> s.id()<=boundary && s.measuredAt()>=from && s.measuredAt()<to
                    && (s.measuredAt()<time || s.measuredAt()==time && s.id()<id))
                    .sorted(Comparator.comparingLong(PingSample::measuredAt).thenComparingLong(PingSample::id).reversed())
                    .limit(size).toList();
            });
    }
    static void refreshFully(PacketLossAnalysisService service, long now) {
        for (int i=0; i<10000; i++) {
            service.refreshAt(now);
            if (service.loadStatus().complete()) return;
        }
        throw new AssertionError("Bootstrap did not complete");
    }
}
