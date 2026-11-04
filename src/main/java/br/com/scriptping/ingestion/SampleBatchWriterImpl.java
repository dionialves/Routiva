package br.com.scriptping.ingestion;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

class SampleBatchWriterImpl implements SampleBatchWriter {

    private final JdbcTemplate jdbc;

    SampleBatchWriterImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insertSamples(List<Row> rows) {
        var parameters = rows.stream().map(row -> new Object[] {
            row.hostId(), row.target(), row.measuredAt(), row.latency(), row.error(),
            row.latencyLimit(), row.collectorId(), row.attemptId(), row.batchId(), row.sequence(),
            row.scheduledAt(), row.sentAt(), row.receivedAt(), row.rttNs(), row.status(),
            row.errorCode(), row.collectorId(), row.attemptId(),
        }).toList();
        jdbc.batchUpdate("""
            INSERT INTO ping_sample(host_id, target, measured_at, latency, error, latency_limit,
                collector_id, attempt_id, batch_id, attempt_sequence, scheduled_at_ns, sent_at_ns,
                received_at_ns, rtt_ns, collection_status, error_code)
            SELECT ?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?
            WHERE NOT EXISTS (SELECT 1 FROM ping_sample WHERE collector_id = ? AND attempt_id = ?)
            """, parameters);
    }
}
