package br.com.scriptping;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;

/** One bounded SQL aggregation per transaction; drain backlog without a minute wait. */
@Service
public class MinuteAggregationService {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger("routiva.indicators");
    private final SampleStore samples;
    private final MinuteAggregationStore summaries;
    private final int pageSize;
    public MinuteAggregationService(SampleStore samples, MinuteAggregationStore summaries,
        @Value("${indicators.aggregation.page-size:10000}") int pageSize) {
        if(pageSize<1 || pageSize>100000) throw new IllegalArgumentException("Aggregation page size must be between 1 and 100000");
        this.samples=samples;this.summaries=summaries;this.pageSize=pageSize;
    }
    @Scheduled(fixedDelayString="${indicators.aggregation.interval-ms:60000}",initialDelayString="${indicators.aggregation.initial-delay-ms:0}",scheduler="indicatorScheduler")
    public synchronized void aggregate() {
        long started=System.nanoTime();
        try {
            long reference=System.currentTimeMillis();
            long boundary=samples.committedBoundary(); // Guard released before any aggregation SQL.
            MinuteAggregationStore.Batch batch;
            do {
                batch=summaries.aggregate(boundary,pageSize,reference);
                LOG.info("event=MINUTE_BATCH_COMMITTED cursor={} boundary={} pings={} summaries={} pending={}",batch.cursor(),batch.boundary(),batch.rows(),batch.summaries(),batch.cursor()<boundary);
            } while(batch.cursor()<boundary && !Thread.currentThread().isInterrupted());
            LOG.info("event=MINUTE_AGGREGATION_COMPLETED cursor={} boundary={} total_ms={}",batch.cursor(),boundary,(System.nanoTime()-started)/1000000);
        } catch(RuntimeException e) {
            LOG.error("event=MINUTE_AGGREGATION_FAILED next_cycle=retry error={}",e.getClass().getSimpleName(),e);
            throw e;
        }
    }
}
