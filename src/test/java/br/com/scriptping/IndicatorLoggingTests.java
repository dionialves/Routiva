package br.com.scriptping;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;

class IndicatorLoggingTests {
    private final SampleStore store = mock(SampleStore.class);
    private final List<PingSample> rows = new ArrayList<>();
    private final PacketLossAnalysisService service = new PacketLossAnalysisService(store, new PacketLossPolicy(), 1000, 2000);
    private final Logger logger = (Logger) LoggerFactory.getLogger("routiva.indicators");
    private final ListAppender<ILoggingEvent> events = new ListAppender<>();
    private Level previousLevel;
    private final long now = 100_000_000L;

    @BeforeEach
    void setup() {
        previousLevel = logger.getLevel();
        logger.setLevel(Level.INFO);
        events.start();
        logger.addAppender(events);
        when(store.committedBoundary()).thenAnswer(i -> rows.stream().mapToLong(PingSample::id).max().orElse(0));
        doAnswer(i -> {
            long after = i.getArgument(0), end = i.getArgument(1), cutoff = i.getArgument(2);
            Consumer<PingSample> consumer = i.getArgument(3);
            rows.stream().filter(r -> r.id() > after && r.id() <= end && r.measuredAt() >= cutoff).forEach(consumer);
            return null;
        }).when(store).readIncrement(anyLong(), anyLong(), anyLong(), any());
    }

    @AfterEach
    void cleanup() {
        logger.detachAppender(events);
        logger.setLevel(previousLevel);
        events.stop();
    }

    private void add(long id, long time) {
        rows.add(new PingSample(id, "h", "10.0.0.1", time, 10d, null));
    }

    @Test
    void reportsProgressBeforeCompletionAndIncludesCoverage() {
        service.configureIncremental(2, 1000);
        service.refreshAt(now);
        events.list.clear();
        add(1, now - 1000); add(2, now - 3599000); add(3, now + 5000);
        service.refreshAt(now);
        assertThat(events.list.getLast().getFormattedMessage()).contains(
            "event=CYCLE_COMPLETED", "fetched=2 ", "pending_future=0 ", "indicators_published=true");
        assertThat(events.list).noneMatch(e -> e.getFormattedMessage().contains("event=INITIAL_LOAD_COMPLETED"));
        events.list.clear();
        IncrementalTestSupport.refreshFully(service, now + 10000);
        assertThat(events.list).noneMatch(e -> e.getFormattedMessage().contains("HISTORY_LOAD"));
        assertThat(service.loadStatus().complete()).isTrue();
        service.refreshAt(now + 11000);
        String result = events.list.getLast().getFormattedMessage();
        assertThat(result).contains("event=CYCLE_COMPLETED", "fetched=0 ", "incorporated=0 ");
        for (String field : List.of("query_ms", "processing_ms", "total_ms", "availability_ms"))
            assertThat(result).containsPattern(field + "=\\d+");
    }

    @Test
    void failureKeepsPublicationReferenceAndOmitsDatabaseMessages() {
        add(1, now);
        service.refreshAt(now);
        when(store.committedBoundary()).thenThrow(new IllegalStateException("secret database details"));
        assertThatThrownBy(() -> service.refreshAt(now + 10000)).isInstanceOf(IllegalStateException.class);
        var event = events.list.getLast();
        assertThat(event.getLevel()).isEqualTo(Level.ERROR);
        assertThat(event.getFormattedMessage()).contains("event=CYCLE_FAILED", "cycle=2 ",
            "stage=database_boundary", "error=IllegalStateException", "last_published_cycle=1 ",
            "indicators_published=false", "next_cycle=retry").doesNotContain("secret database details");
        doReturn(1L).when(store).committedBoundary();
        service.refreshAt(now + 20000);
        assertThat(events.list.getLast().getFormattedMessage()).contains("event=CYCLE_COMPLETED", "cycle=3 ", "retained_pings=0 ");
    }
}
