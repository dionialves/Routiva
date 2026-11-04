package br.com.scriptping.aggregation;

import java.util.concurrent.TimeUnit;

import br.com.scriptping.ingestion.SampleIngestionService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MinuteRetention {

    private final SampleIngestionService samples;
    private final MinuteAggregationRepository repository;

    MinuteRetention(SampleIngestionService samples, MinuteAggregationRepository repository) {
        this.samples = samples;
        this.repository = repository;
    }

    @Scheduled(fixedDelayString = "${monitor.cleanup-interval-seconds:600}", timeUnit = TimeUnit.SECONDS)
    @Transactional
    public void cleanup() {
        cleanup(samples.retentionCutoff(System.currentTimeMillis()));
    }

    @Transactional
    public void cleanup(long cutoff) {
        long cursor = repository.lockProgress().snapshot().cursor();
        samples.deleteExpiredSamplesAndReceipts(cutoff, cursor);
    }
}
