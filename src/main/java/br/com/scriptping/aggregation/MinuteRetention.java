package br.com.scriptping.aggregation;

import java.util.concurrent.TimeUnit;

import br.com.scriptping.ingestion.SampleIngestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Bounded maintenance, deliberately outside the aggregation/ingestion transaction. */
@Service
public class MinuteRetention {

    private static final Logger LOG = LoggerFactory.getLogger("routiva.indicators");
    private final SampleIngestionService samples;
    private final MinuteAggregationRepository repository;
    private final int batchSize, maxBatches;
    private final boolean enabled;

    MinuteRetention(SampleIngestionService samples, MinuteAggregationRepository repository,
        @Value("${monitor.cleanup-batch-size:1000}") int batchSize,
        @Value("${monitor.cleanup-max-batches:10}") int maxBatches,
        @Value("${monitor.cleanup-enabled:true}") boolean enabled) {
        if (batchSize < 1 || batchSize > 10000 || maxBatches < 1 || maxBatches > 100)
            throw new IllegalArgumentException("Invalid cleanup limits: batch size 1..10000, batches 1..100");
        this.samples = samples;
        this.repository = repository;
        this.batchSize = batchSize;
        this.maxBatches = maxBatches;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${monitor.cleanup-interval-seconds:600}",
        timeUnit = TimeUnit.SECONDS, scheduler = "retentionScheduler")
    public void cleanup() {
        if (enabled) cleanup(samples.retentionCutoff(System.currentTimeMillis()));
    }

    public void cleanup(long cutoff) {
        // A committed, monotonic watermark is sufficient; never lock the progress row.
        long cursor = repository.processedRawCursor();
        int totalSamples = 0, totalReceipts = 0;
        for (int page = 0; page < maxBatches && !Thread.currentThread().isInterrupted(); page++) {
            long started = System.nanoTime();
            try {
                // REQUIRES_NEW on the service: each page commits before the next one.
                var deleted = samples.deleteExpiredSamplesAndReceipts(cutoff, cursor, batchSize);
                totalSamples += deleted.samples();
                totalReceipts += deleted.receipts();
                LOG.info("event=RETENTION_BATCH_COMMITTED page={} raw_deleted={} receipts_deleted={} raw_cursor={} elapsed_ms={}",
                    page + 1, deleted.samples(), deleted.receipts(), cursor,
                    (System.nanoTime() - started) / 1000000);
                if (deleted.samples() < batchSize && deleted.receipts() < batchSize) break;
            } catch (RuntimeException failure) {
                LOG.warn("event=RETENTION_BATCH_FAILED raw_cursor={} raw_deleted={} receipts_deleted={} next_cycle=retry",
                    cursor, totalSamples, totalReceipts, failure);
                return;
            }
        }
    }
}
