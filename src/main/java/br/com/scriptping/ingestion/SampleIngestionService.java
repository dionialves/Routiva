package br.com.scriptping.ingestion;

import br.com.scriptping.IpAddress;
import br.com.scriptping.collector.proto.Batch;
import br.com.scriptping.collector.proto.Status;
import br.com.scriptping.aggregation.MinuteAggregationService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@DependsOnDatabaseInitialization
public class SampleIngestionService {

    // A retenção configurada nunca pode ultrapassar 48 horas.
    private final Duration retention;
    private final double latencyLimit;
    private final SampleIngestionRepository repository;
    private final CollectorBatchRepository receipts;
    private final MinuteAggregationService summaries;

    public SampleIngestionService(
        SampleIngestionRepository repository,
        CollectorBatchRepository receipts,
        MinuteAggregationService summaries,
        @Value("${monitor.retention-hours:48}") long retentionHours,
        @Value("${monitor.cleanup-interval-seconds:600}") long cleanupSeconds,
        @Value("${monitor.latency-limit-ms:20}") double latencyLimit
    ) {
        if (
            !Double.isFinite(latencyLimit) || latencyLimit <= 0 || latencyLimit > 60000
        ) throw new IllegalArgumentException(
            "monitor.latency-limit-ms deve estar entre 0 e 60000 ms."
        );
        this.latencyLimit = latencyLimit;
        if (cleanupSeconds < 1) throw new IllegalArgumentException(
            "monitor.cleanup-interval-seconds deve ser positivo."
        );
        if (retentionHours < 1) throw new IllegalArgumentException(
            "monitor.retention-hours deve ser positivo."
        );
        this.repository = repository;
        this.receipts = receipts;
        this.summaries = summaries;
        this.retention = Duration.ofHours(Math.min(retentionHours, 48));
    }

    public double latencyLimit() {
        return latencyLimit;
    }

    /** Upgrade one bounded page per transaction before accepting live batches. */
    @Transactional
    public long migrateLegacyPage() {
        repository.lockIngestion();
        long boundary = summaries.legacyBoundary();
        var page = summaries.aggregate(boundary, 10000, System.currentTimeMillis());
        return Math.max(0, boundary - page.cursor());
    }

    /** The receipt and all summaries commit together. The single ingestion writer retries the same identity. */
    @Transactional
    public void saveBatch(Batch batch) {
        repository.lockIngestion();
        String collector = batch.getCollectorId();
        if (
            collector.isBlank() ||
            collector.length() > 64 ||
            batch.getBatchId() <= 0 ||
            batch.getResultsCount() > 5000
        ) throw new IllegalArgumentException("Invalid batch");
        var receiptId = new CollectorBatchKey(collector, batch.getBatchId());
        if (receipts.existsById(receiptId)) return;
        long now = System.currentTimeMillis(),
            cutoff = retentionCutoff(now);
        List<PingSample> rows = new ArrayList<>();
        for (var r : batch.getResultsList()) {
            if (
                !collector.equals(r.getCollectorId()) ||
                r.getAttemptId() <= 0 ||
                r.getHostId().isBlank() ||
                r.getHostId().length() > 255 ||
                !IpAddress.isValid(r.getTarget()) ||
                r.getScheduledAt() <= 0 ||
                r.getScheduledAt() / 1000000 > now + 60000 ||
                r.getStatusValue() < 1 ||
                r.getStatusValue() > 6 ||
                r.getRttNs() < 0 ||
                r.getErrorCode().length() > 255 ||
                !Double.isFinite(r.getLatencyLimitMs()) ||
                r.getLatencyLimitMs() < 0 ||
                r.getLatencyLimitMs() > 60000
            ) throw new IllegalArgumentException("Invalid result");
            long measured = r.getScheduledAt() / 1000000;
            if (measured < cutoff) continue; // Late replay must not resurrect expired history.
            Double latency =
                r.getStatus() == Status.SUCCESS
                    ? r.getRttNs() / 1000000d
                    : null;
            String error = switch (r.getStatus()) {
                case SUCCESS -> null;
                case TIMEOUT -> "Tempo esgotado";
                case ICMP_ERROR -> "Sem resposta ICMP";
                default -> "Coletor: " + r.getStatus().name();
            };
            rows.add(new PingSample(0, r.getHostId(), r.getTarget(), measured, latency,
                error, r.getLatencyLimitMs() > 0 ? r.getLatencyLimitMs() : latencyLimit));
        }
        summaries.ingest(rows, now);
        receipts.save(new CollectorBatchReceipt(receiptId, now));
    }

    /** Persist a terminal rejection before releasing the producer's batch. No raw data is stored. */
    @Transactional
    public void rejectBatch(Batch batch, String reason) {
        repository.lockIngestion();
        var id = new CollectorBatchKey(batch.getCollectorId(), batch.getBatchId());
        if (!receipts.existsById(id)) receipts.save(new CollectorBatchReceipt(id,
            System.currentTimeMillis(), reason.substring(0, Math.min(reason.length(), 255))));
    }

    public long retentionCutoff(long now) {
        return now - retention.toMillis();
    }

    @Transactional
    public void deleteExpiredSamplesAndReceipts(long cutoff, long processedCursor) {
        repository.deleteExpiredSamples(cutoff, processedCursor);
        receipts.deleteExpired(cutoff - Duration.ofDays(7).toMillis());
    }
}
