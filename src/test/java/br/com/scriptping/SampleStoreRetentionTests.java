package br.com.scriptping;

import br.com.scriptping.ingestion.CollectorBatchRepository;
import br.com.scriptping.ingestion.SampleIngestionService;
import br.com.scriptping.ingestion.SampleIngestionRepository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import br.com.scriptping.aggregation.MinuteRetention;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
    properties = {
        "indicators.aggregation.initial-delay-ms=3600000",
        "spring.datasource.url=jdbc:h2:mem:retention;DB_CLOSE_DELAY=-1",
        "zabbix.url=",
        "zabbix.username=",
        "zabbix.password=",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "monitor.retention-hours=48",
        "monitor.cleanup-batch-size=2",
        "monitor.cleanup-max-batches=3",
    }
)
class SampleStoreRetentionTests {

    @org.springframework.test.context.DynamicPropertySource
    static void database(org.springframework.test.context.DynamicPropertyRegistry properties) {
        String url = System.getenv("ROUTIVA_TEST_JDBC_URL");
        if (url != null) {
            properties.add("spring.datasource.url", () -> url);
            properties.add("spring.datasource.username", () -> "postgres");
            properties.add("spring.datasource.password", () -> "routiva-test-only");
        }
    }


    @Autowired
    MinuteRetention retention;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SampleIngestionRepository repository;

    @Autowired
    CollectorBatchRepository receipts;

    @Autowired
    javax.sql.DataSource dataSource;

    @Autowired
    org.springframework.transaction.PlatformTransactionManager transactions;

    @org.junit.jupiter.api.BeforeEach
    void clearSamples() {
        jdbc.update("DELETE FROM ping_sample");
        jdbc.update("DELETE FROM collector_batch");
    }

    @Test
    void configuredRuleDeletesOnlySamplesOlderThan48Hours() {
        long now = System.currentTimeMillis();
        insert("old-response", now - Duration.ofHours(49).toMillis(), 10d, null);
        insert("old-failure", now - Duration.ofHours(48).toMillis() - 5000, null, "Tempo esgotado");
        insert("recent-response", now - Duration.ofHours(48).toMillis() + 60000, 12d, null);
        insert("recent-failure", now - 1000, null, "Tempo esgotado");
        markProcessed();
        retention.cleanup();
        assertThat(
            jdbc.queryForList("SELECT host_id FROM ping_sample ORDER BY host_id", String.class)
        ).containsExactly("recent-failure", "recent-response");
    }

    @Test
    void retentionCanBeChangedThroughConfiguration() {
        long now = System.currentTimeMillis();
        insert("custom-old", now - Duration.ofHours(3).toMillis(), 10d, null);
        insert("custom-recent", now - Duration.ofHours(1).toMillis(), 10d, null);
        markProcessed();
        retention.cleanup(new SampleIngestionService(repository, receipts, null, 2, 600, 20d).retentionCutoff(now));
        assertThat(
            jdbc.queryForList(
                "SELECT host_id FROM ping_sample WHERE host_id LIKE 'custom-%' ORDER BY host_id",
                String.class
            )
        ).containsExactly("custom-recent");
    }

    @Test
    void retentionNeverExceeds48HoursEvenWithHigherConfiguration() {
        long now = System.currentTimeMillis();
        insert("over-limit", now - Duration.ofHours(49).toMillis(), 10d, null);
        insert("within-limit", now - Duration.ofHours(47).toMillis(), 10d, null);
        markProcessed();
        retention.cleanup(new SampleIngestionService(repository, receipts, null, 168, 600, 20d).retentionCutoff(now));
        assertThat(
            jdbc.queryForList("SELECT host_id FROM ping_sample ORDER BY host_id", String.class)
        ).containsExactly("within-limit");
    }

    @Test
    void rejectsNonPositiveRetentionOrCleanupInterval() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            new SampleIngestionService(repository, receipts, null, 0, 600, 20d)
        ).isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            new SampleIngestionService(repository, receipts, null, 48, 0, 20d)
        ).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cleanupIsBoundedAndNeverDeletesUnprocessedOrRecentSamples() {
        long now = System.currentTimeMillis();
        long expired = now - Duration.ofHours(49).toMillis();
        for (int i = 0; i < 10; i++) insert("expired-" + i, expired, 10d, null);
        insert("recent", now, 10d, null);
        markProcessed();
        insert("unprocessed", expired, 10d, null);

        retention.cleanup(now - Duration.ofHours(48).toMillis());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ping_sample WHERE host_id LIKE 'expired-%'", Long.class))
            .isEqualTo(4); // Three independent transactions, two rows each.
        retention.cleanup(now - Duration.ofHours(48).toMillis());
        assertThat(jdbc.queryForList("SELECT host_id FROM ping_sample ORDER BY host_id", String.class))
            .containsExactly("recent", "unprocessed");
    }

    @Test
    void cleanupDoesNotWaitForTheProgressLockUsedByIngestion() throws Exception {
        long now = System.currentTimeMillis();
        insert("expired", now - Duration.ofHours(49).toMillis(), 10d, null);
        markProcessed();
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement(
                "SELECT id FROM ping_aggregation_progress WHERE id=1 FOR UPDATE")) {
                statement.executeQuery().close();
            }
            try {
                // The old implementation blocked here, keeping new batches without ACK.
                executor.submit(() -> retention.cleanup(now - Duration.ofHours(48).toMillis()))
                    .get(3, java.util.concurrent.TimeUnit.SECONDS);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ping_sample", Long.class)).isZero();
            } finally {
                connection.rollback();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void cleanupPagesCommitIndependentlyOfTheCallingTransaction() {
        long now = System.currentTimeMillis();
        for (int i = 0; i < 5; i++) insert("expired-" + i,
            now - Duration.ofHours(49).toMillis(), 10d, null);
        markProcessed();
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status -> {
            retention.cleanup(now - Duration.ofHours(48).toMillis());
            status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ping_sample", Long.class)).isZero();
    }

    @Test
    void receiptCleanupIsBoundedAndKeepsTheReplayWindow() {
        long now = System.currentTimeMillis();
        for (int i = 1; i <= 8; i++) jdbc.update(
            "INSERT INTO collector_batch(collector_id,batch_id,committed_at) VALUES('expired',?,?)",
            i, now - Duration.ofDays(10).toMillis());
        jdbc.update("INSERT INTO collector_batch(collector_id,batch_id,committed_at) VALUES('protected',1,?)",
            now - Duration.ofDays(8).toMillis());
        retention.cleanup(now - Duration.ofHours(48).toMillis());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM collector_batch WHERE collector_id='expired'", Long.class))
            .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM collector_batch WHERE collector_id='protected'", Long.class))
            .isEqualTo(1);
    }

    private void markProcessed() {
        jdbc.update("UPDATE ping_aggregation_progress SET cursor_id=(SELECT COALESCE(MAX(id),0) FROM ping_sample), raw_cursor_id=(SELECT COALESCE(MAX(id),0) FROM ping_sample) WHERE id=1");
    }

    private void insert(String hostId, long at, Double latency, String error) {
        jdbc.update(
            "INSERT INTO ping_sample(host_id,target,measured_at,latency,error) VALUES(?,?,?,?,?)",
            hostId,
            "127.0.0.1",
            at,
            latency,
            error
        );
    }
}
