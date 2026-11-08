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
    }
)
class SampleStoreRetentionTests {

    @Autowired
    MinuteRetention retention;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SampleIngestionRepository repository;

    @Autowired
    CollectorBatchRepository receipts;

    @org.junit.jupiter.api.BeforeEach
    void clearSamples() {
        jdbc.update("DELETE FROM ping_sample");
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
