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
    void reportsInitialLoadWindowExpirationsAndFutureReleasesWithoutDoubleCounting() {
        add(1, now - 299000); add(2, now - 3599000); add(3, now - 28799000); add(4, now + 5000);
        service.refreshAt(now);
        assertThat(events.list).hasSize(1);
        assertThat(events.list.getFirst().getFormattedMessage()).contains(
            "event=INITIAL_LOAD_COMPLETED", "cycle=1 ", "fetched=4 ", "incorporated=3 ",
            "pending_future=1 ", "retained_pings=3 ", "late_arrivals=0 ");
        add(5, now - 1000);
        rows.add(new PingSample(6, "invalid", "invalid", now, 10d, null));
        add(7, now + 30000);
        service.refreshAt(now + 10000);
        assertThat(events.list).hasSize(2);
        String result = events.list.getLast().getFormattedMessage();
        assertThat(result).contains("event=CYCLE_COMPLETED", "cycle=2 ", "fetched=3 ",
            "incorporated=2 ", "late_arrivals=1 ", "future_queued=1 ", "future_released=1 ",
            "pending_future=1 ", "discarded=1 ", "expired_5m=1 ", "expired_1h=1 ",
            "expired_8h=1 ", "removed_pings=1 ", "retained_pings=4 ", "indicators_published=true");
        service.current(List.of(new Host("h", "Host", "10.0.0.1", List.of())));
        assertThat(events.list).hasSize(2);
        for (String field : List.of("query_ms", "processing_ms", "total_ms", "availability_ms", "availability_buckets", "availability_hosts")) {
            assertThat(result).containsPattern(field + "=\\d+");
        }
        service.refreshAt(now + 11000);
        assertThat(events.list.getLast().getFormattedMessage()).contains("fetched=0 ", "incorporated=0 ", "removed_pings=0 ");
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
            "indicators_published=false", "next_cycle=initial_load").doesNotContain("secret database details");
        doReturn(1L).when(store).committedBoundary();
        service.refreshAt(now + 20000);
        assertThat(events.list.getLast().getFormattedMessage()).contains("event=INITIAL_LOAD_COMPLETED", "cycle=3 ", "retained_pings=1 ");
    }
}
