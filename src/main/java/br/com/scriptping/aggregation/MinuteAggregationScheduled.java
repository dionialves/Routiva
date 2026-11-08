package br.com.scriptping.aggregation;

import br.com.scriptping.availability.AvailabilitySnapshotCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Drain the committed backlog, with one transaction per batch. */
@Service
public class MinuteAggregationScheduled {

    private static final Logger LOG = LoggerFactory.getLogger("routiva.indicators");
    private final MinuteAggregationService summaries;
    private final int pageSize;
    private final AvailabilitySnapshotCache availability;

    public MinuteAggregationScheduled(
        MinuteAggregationService summaries,
        AvailabilitySnapshotCache availability,
        @Value("${indicators.aggregation.page-size:10000}")
        int pageSize
    ) {
        if (pageSize < 1 || pageSize > 100000)
            throw new IllegalArgumentException(
                "Aggregation page size must be between 1 and 100000"
            );

        this.summaries = summaries;
        this.availability = availability;
        this.pageSize = pageSize;
    }

    @Scheduled(
        fixedRateString = "${indicators.aggregation.interval-ms:60000}",
        initialDelayString = "${indicators.aggregation.initial-delay-ms:0}",
        scheduler = "indicatorScheduler"
    )
    public synchronized void aggregate() {
        long started = System.nanoTime();

        try {
            long reference = System.currentTimeMillis();
            long boundary = summaries.committedBoundary();

            MinuteAggregationService.Batch batch;

            do {
                batch = summaries.aggregate(boundary, pageSize, reference);
                LOG.info(
                    "event=MINUTE_BATCH_COMMITTED cursor={} boundary={} pings={} summaries={} pending={}",
                    batch.cursor(),
                    batch.boundary(),
                    batch.rows(),
                    batch.summaries(),
                    batch.cursor() < boundary
                );

            } while (batch.cursor() < boundary && !Thread.currentThread().isInterrupted());

            if (batch.cursor() == boundary && !Thread.currentThread().isInterrupted()) availability.refresh();

            LOG.info(
                "event=MINUTE_AGGREGATION_COMPLETED cursor={} boundary={} total_ms={}",
                batch.cursor(),
                boundary,
                (System.nanoTime() - started) / 1000000
            );

        } catch (RuntimeException e) {
            LOG.error(
                "event=MINUTE_AGGREGATION_FAILED next_cycle=retry error={}",
                e.getClass().getSimpleName(),
                e
            );
            throw e;
        }
    }
}
