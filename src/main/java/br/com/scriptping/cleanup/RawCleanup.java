package br.com.scriptping.cleanup;

import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class RawCleanup {
    private static final Logger LOG = LoggerFactory.getLogger("routiva.indicators");
    private final CleanupStore store;
    private final CleanupQueue queue;
    private final boolean enabled;
    private final boolean keepRaw;
    private final long retentionMillis;
    private final int batchSize;

    public RawCleanup(CleanupStore store, CleanupQueue queue,
        @Value("${monitor.cleanup-enabled:true}") boolean enabled,
        @Value("${monitor.keep-raw:no}") boolean keepRaw,
        @Value("${monitor.raw-retention-minutes:2880}") long minutes,
        @Value("${monitor.cleanup-batch-size:1000}") int batchSize) {
        if (minutes < 1 || batchSize < 1 || batchSize > 10000)
            throw new IllegalArgumentException("Expected positive retention and batch size 1..10000");
        this.store = store;
        this.queue = queue;
        this.enabled = enabled;
        this.keepRaw = keepRaw;
        this.retentionMillis = Duration.ofMinutes(minutes).toMillis();
        this.batchSize = batchSize;
    }

    @Scheduled(cron = "0 0 * * * *", scheduler = "cleanupScheduler")
    public void cleanup() {
        if (enabled) cleanup(keepRaw ? System.currentTimeMillis() - retentionMillis : Long.MAX_VALUE);
    }

    public void cleanup(long cutoff) {
        queue.drain(enqueue -> {
            long cursor = store.cursor();
            // Receipts protect the 48-hour ingestion replay window plus seven days.
            long receiptsBefore = System.currentTimeMillis() - Duration.ofDays(9).toMillis();
            enqueue.accept(() -> receipts(enqueue, receiptsBefore));
            enqueue.accept(() -> planRaw(enqueue, cutoff, cursor));
        });
    }

    private void planRaw(Consumer<Runnable> enqueue, long cutoff, long cursor) {
        Long oldest = store.oldest();
        if (oldest == null || oldest >= cutoff) return;
        long end = Math.min(cutoff, store.latest() + 1);
        LOG.info("event=CLEANUP_RAW_SCAN raw_cursor={} oldest_ms={} end_exclusive_ms={}", cursor, oldest, end);
        if (oldest < end) discover(enqueue, cursor, oldest, end, end - oldest);
    }

    private void receipts(Consumer<Runnable> enqueue, long cutoff) {
        if (store.deleteReceipts(cutoff, batchSize) == batchSize)
            enqueue.accept(() -> receipts(enqueue, cutoff));
    }

    private void discover(Consumer<Runnable> enqueue, long cursor, long start, long end, long window) {
        long until = start + Math.min(end - start, window);
        List<Long> ids;
        try {
            ids = store.discover(start, until, cursor, batchSize);
        } catch (DataAccessException timeout) {
            if (!isTimeout(timeout) || until - start <= 1) throw timeout;
            long reduced = Math.max(1, (until - start) / 2);
            LOG.warn("event=CLEANUP_DISCOVERY_TIMEOUT window_ms={}", reduced);
            enqueue.accept(() -> discover(enqueue, cursor, start, end, reduced));
            return;
        }
        if (!ids.isEmpty()) enqueue.accept(() -> store.delete(ids));
        long next = ids.size() == batchSize ? start : until;
        if (next < end) enqueue.accept(() -> discover(enqueue, cursor, next, end, window));
    }

    private boolean isTimeout(DataAccessException failure) {
        // PostgreSQL reports JDBC statement cancellation as SQLSTATE 57014.
        return failure instanceof QueryTimeoutException
            || failure.getMostSpecificCause() instanceof SQLException sql && "57014".equals(sql.getSQLState());
    }
}
