package br.com.scriptping.ingestion;

import br.com.scriptping.IpAddress;
import br.com.scriptping.collector.proto.Batch;
import br.com.scriptping.collector.proto.Status;
import br.com.scriptping.ingestion.SampleBatchWriter.Row;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@DependsOnDatabaseInitialization
public class SampleIngestionService {

    private static final Duration REPLAY_WINDOW = Duration.ofHours(48);
    private final double latencyLimit;
    private final SampleIngestionRepository repository;
    private final CollectorBatchRepository receipts;

    public SampleIngestionService(
        SampleIngestionRepository repository,
        CollectorBatchRepository receipts,
        @Value("${monitor.latency-limit-ms:20}") double latencyLimit
    ) {
        if (
            !Double.isFinite(latencyLimit) || latencyLimit <= 0 || latencyLimit > 60000
        ) throw new IllegalArgumentException(
            "monitor.latency-limit-ms deve estar entre 0 e 60000 ms."
        );
        this.latencyLimit = latencyLimit;
        this.repository = repository;
        this.receipts = receipts;
    }

    public double latencyLimit() {
        return latencyLimit;
    }

    /** Timing is published only after the Spring transaction has committed. */
    public static final class WriteResult {
        public int results, rejectedResults, duplicateBatches;
        public long lockNanos, prepareNanos, insertNanos, commitNanos;
    }

    @Transactional
    public void saveBatch(Batch batch) {
        persist(List.of(batch), false);
    }

    /** All raw rows and receipts in the group commit atomically, including terminal rejections. */
    @Transactional
    public WriteResult saveBatches(List<Batch> batches) {
        if (batches.isEmpty() || batches.size() > 32
            || batches.stream().mapToInt(Batch::getResultsCount).sum() > 20000)
            throw new IllegalArgumentException("Invalid persistence group");
        return persist(batches, true);
    }

    private WriteResult persist(List<Batch> batches, boolean rejectInvalid) {
        var timing = new WriteResult();
        long started = System.nanoTime();
        repository.lockIngestion();
        timing.lockNanos = System.nanoTime() - started;
        started = System.nanoTime();
        long now = System.currentTimeMillis(), cutoff = now - REPLAY_WINDOW.toMillis();
        var identities = new HashSet<CollectorBatchKey>();
        var pendingReceipts = new ArrayList<CollectorBatchReceipt>();
        var rows = new ArrayList<Row>();
        for (var batch : batches) {
            validateIdentity(batch);
            var id = new CollectorBatchKey(batch.getCollectorId(), batch.getBatchId());
            if (!identities.add(id) || receipts.existsById(id)) {
                timing.duplicateBatches++;
                continue;
            }
            try {
                rows.addAll(rows(batch, now, cutoff));
                pendingReceipts.add(new CollectorBatchReceipt(id, now));
                timing.results += batch.getResultsCount();
            } catch (IllegalArgumentException invalid) {
                if (!rejectInvalid) throw invalid;
                pendingReceipts.add(new CollectorBatchReceipt(id, now, invalid.getMessage()));
                timing.rejectedResults += batch.getResultsCount();
                org.slf4j.LoggerFactory.getLogger(getClass()).error(
                    "event=RAW_BATCH_REJECTED collector={} batch={} results={} reason={}",
                    batch.getCollectorId(), batch.getBatchId(), batch.getResultsCount(), invalid.getMessage());
            }
        }
        timing.prepareNanos = System.nanoTime() - started;
        started = System.nanoTime();
        if (!rows.isEmpty()) repository.insertSamples(rows);
        for (var receipt : pendingReceipts) receipts.save(receipt);
        timing.insertNanos = System.nanoTime() - started;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            private long beforeCommit;
            @Override public void beforeCommit(boolean readOnly) { beforeCommit = System.nanoTime(); }
            @Override public void afterCommit() { timing.commitNanos = System.nanoTime() - beforeCommit; }
        });
        return timing;
    }

    private void validateIdentity(Batch batch) {
        String collector = batch.getCollectorId();
        if (
            collector.isBlank() ||
            collector.length() > 64 ||
            batch.getBatchId() <= 0 ||
            batch.getResultsCount() > 5000
        ) throw new IllegalArgumentException("Invalid batch");
    }

    private List<Row> rows(Batch batch, long now, long cutoff) {
        String collector = batch.getCollectorId();
        List<Row> rows = new ArrayList<>();
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
            rows.add(new Row(r.getHostId(), r.getTarget(), measured, latency, error,
                r.getLatencyLimitMs() > 0 ? r.getLatencyLimitMs() : latencyLimit,
                collector, r.getAttemptId(), batch.getBatchId(), r.getSequence(),
                r.getScheduledAt(), r.getSentAt(), r.getReceivedAt(), r.getRttNs(),
                r.getStatus().name(), r.getErrorCode()));
        }
        return rows;
    }

    /** Persist a terminal rejection before releasing the producer's batch. No raw data is stored. */
    @Transactional
    public void rejectBatch(Batch batch, String reason) {
        repository.lockIngestion();
        var id = new CollectorBatchKey(batch.getCollectorId(), batch.getBatchId());
        if (!receipts.existsById(id)) receipts.save(new CollectorBatchReceipt(id,
            System.currentTimeMillis(), reason.substring(0, Math.min(reason.length(), 255))));
    }
}
