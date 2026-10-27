package br.com.scriptping;

import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;

@Repository
@org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization
public class SampleStore {

    // A retenção configurada nunca pode ultrapassar 48 horas.
    private final Duration retention;
    private final double latencyLimit;
    private final JdbcTemplate jdbc;
    private final org.springframework.context.ApplicationEventPublisher publisher;

    public SampleStore(JdbcTemplate jdbc, long retentionHours, long cleanupSeconds) {
        this(jdbc, retentionHours, cleanupSeconds, event -> {});
    }

    public SampleStore(
        JdbcTemplate jdbc,
        @org.springframework.beans.factory.annotation.Value(
            "${monitor.retention-hours:48}"
        ) long retentionHours,
        @org.springframework.beans.factory.annotation.Value(
            "${monitor.cleanup-interval-seconds:600}"
        ) long cleanupSeconds,
        org.springframework.context.ApplicationEventPublisher publisher
    ) {
        this(jdbc, retentionHours, cleanupSeconds, publisher, 20d);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SampleStore(
        JdbcTemplate jdbc,
        @org.springframework.beans.factory.annotation.Value(
            "${monitor.retention-hours:48}"
        ) long retentionHours,
        @org.springframework.beans.factory.annotation.Value(
            "${monitor.cleanup-interval-seconds:600}"
        ) long cleanupSeconds,
        org.springframework.context.ApplicationEventPublisher publisher,
        @org.springframework.beans.factory.annotation.Value(
            "${monitor.latency-limit-ms:20}"
        ) double latencyLimit
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
        this.jdbc = jdbc;
        this.retention = Duration.ofHours(Math.min(retentionHours, 48));
        this.publisher = publisher;
    }

    public double latencyLimit() {
        return latencyLimit;
    }

    public PingSample save(Host host, PingProbe.Result result) {
        long now = System.currentTimeMillis();
        var key = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(
                "INSERT INTO ping_sample(host_id,target,measured_at,latency,error,latency_limit) VALUES(?,?,?,?,?,?)",
                new String[] { "id" }
            );
            statement.setString(1, host.id());
            statement.setString(2, host.ip());
            statement.setLong(3, now);
            statement.setObject(4, result.latency());
            statement.setString(5, result.error());
            statement.setDouble(6, latencyLimit);
            return statement;
        }, key);
        var sample = new PingSample(
            Objects.requireNonNull(key.getKey()).longValue(),
            host.id(),
            host.ip(),
            now,
            result.latency(),
            result.error()
        );
        if (
            org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
        ) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        publisher.publishEvent(sample);
                    }
                }
            );
        } else publisher.publishEvent(sample);
        return sample;
    }

    /** The receipt and all rows commit together. The single ingestion writer retries the same identity. */
    @org.springframework.transaction.annotation.Transactional
    public void saveBatch(br.com.scriptping.collector.proto.Batch batch) {
        String collector = batch.getCollectorId();
        if (
            collector.isBlank() ||
            collector.length() > 64 ||
            batch.getBatchId() <= 0 ||
            batch.getResultsCount() > 5000
        ) throw new IllegalArgumentException("Invalid batch");
        if (
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM collector_batch WHERE collector_id=? AND batch_id=?",
                Integer.class,
                collector,
                batch.getBatchId()
            ) > 0
        ) return;
        long now = System.currentTimeMillis(),
            cutoff = now - retention.toMillis();
        List<Object[]> rows = new ArrayList<>();
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
                r.getStatus() == br.com.scriptping.collector.proto.Status.SUCCESS
                    ? r.getRttNs() / 1000000d
                    : null;
            String error = switch (r.getStatus()) {
                case SUCCESS -> null;
                case TIMEOUT -> "Tempo esgotado";
                case ICMP_ERROR -> "Sem resposta ICMP";
                default -> "Coletor: " + r.getStatus().name();
            };
            rows.add(new Object[] {
                r.getHostId(),
                r.getTarget(),
                measured,
                latency,
                error,
                r.getLatencyLimitMs() > 0 ? r.getLatencyLimitMs() : 20d,
                collector,
                r.getAttemptId(),
                batch.getBatchId(),
                r.getSequence(),
                r.getScheduledAt(),
                r.getSentAt(),
                r.getReceivedAt(),
                r.getRttNs(),
                r.getStatus().name(),
                r.getErrorCode(),
                collector,
                r.getAttemptId(),
            });
        }
        jdbc.batchUpdate(
            "INSERT INTO ping_sample(host_id,target,measured_at,latency,error,latency_limit,collector_id,attempt_id,batch_id,attempt_sequence,scheduled_at_ns,sent_at_ns,received_at_ns,rtt_ns,collection_status,error_code) SELECT ?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,? WHERE NOT EXISTS (SELECT 1 FROM ping_sample WHERE collector_id=? AND attempt_id=?)",
            rows
        );
        jdbc.update(
            "INSERT INTO collector_batch(collector_id,batch_id,committed_at) VALUES(?,?,?)",
            collector,
            batch.getBatchId(),
            now
        );
        List<PingSample> saved = jdbc.query(
            "SELECT id,host_id,target,measured_at,latency,error FROM ping_sample WHERE collector_id=? AND batch_id=? ORDER BY measured_at,id",
            (rs, n) ->
                new PingSample(
                    rs.getLong("id"),
                    rs.getString("host_id"),
                    rs.getString("target"),
                    rs.getLong("measured_at"),
                    (Double) rs.getObject("latency"),
                    rs.getString("error")
                ),
            collector,
            batch.getBatchId()
        );
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
            new org.springframework.transaction.support.TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    for (var sample : saved) publisher.publishEvent(sample);
                }
            }
        );
    }

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public void readRecent(long cutoff, java.util.function.Consumer<PingSample> consumer) {
        jdbc.query(
            connection -> {
                var query = connection.prepareStatement(
                    "SELECT id,host_id,target,measured_at,latency,error FROM ping_sample WHERE measured_at>=? ORDER BY measured_at,id"
                );
                query.setLong(1, cutoff);
                query.setFetchSize(1000);
                return query;
            },
            (org.springframework.jdbc.core.RowCallbackHandler) row -> {
                Number latency = (Number) row.getObject("latency");
                consumer.accept(
                    new PingSample(
                        row.getLong("id"),
                        row.getString("host_id"),
                        row.getString("target"),
                        row.getLong("measured_at"),
                        latency == null ? null : latency.doubleValue(),
                        row.getString("error")
                    )
                );
            }
        );
    }

    public record Stats(
        Double hour,
        Double minute,
        Double current,
        Long measuredAt,
        String error,
        long attempts,
        long failures,
        long attemptsLastHour,
        long lossesLastHour,
        long attemptsLastMinute,
        long lossesLastMinute,
        Double eightHours,
        Double latencyHour,
        Double fiveMinutes,
        long attemptsEightHours,
        long lossesEightHours,
        long attemptsLatencyHour,
        long lossesLatencyHour,
        long attemptsFiveMinutes,
        long lossesFiveMinutes
    ) {}

    // PROVISÓRIO: executa ao iniciar e no intervalo configurado; substituir pela futura política de retenção.
    @Scheduled(
        fixedDelayString = "${monitor.cleanup-interval-seconds:600}",
        timeUnit = java.util.concurrent.TimeUnit.SECONDS
    )
    public void cleanup() {
        jdbc.update(
            "DELETE FROM ping_sample WHERE measured_at < ?",
            System.currentTimeMillis() - retention.toMillis()
        );
        jdbc.update(
            "DELETE FROM collector_batch WHERE committed_at < ?",
            System.currentTimeMillis() - retention.toMillis()
        );
    }
}
