package br.com.scriptping;

import static org.assertj.core.api.Assertions.*;

import br.com.scriptping.collector.proto.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
    properties = {
        "collector.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:collector;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "zabbix.url=",
        "zabbix.username=",
        "zabbix.password=",
    }
)
class CollectorPersistenceTests {

    @Autowired
    SampleStore store;

    @Autowired
    JdbcTemplate jdbc;

    private Result result(String id, long attempt, Status status, long at) {
        return Result.newBuilder()
            .setCollectorId(id)
            .setHostId("h")
            .setTarget("127.0.0.1")
            .setAttemptId(attempt)
            .setSequence(attempt)
            .setScheduledAt(at)
            .setSentAt(at)
            .setReceivedAt(at + 1000000)
            .setRttNs(1000000)
            .setStatus(status)
            .build();
    }

    @Test
    void retriesAreIdempotentAndOriginalTimesSurvive() {
        long at = (System.currentTimeMillis() - 5000) * 1000000;
        var batch = Batch.newBuilder()
            .setCollectorId("retry")
            .setBatchId(1)
            .setProtocolVersion(1)
            .addResults(result("retry", 2, Status.TIMEOUT, at + 1000000000))
            .addResults(result("retry", 1, Status.SUCCESS, at))
            .build();
        store.saveBatch(batch);
        store.saveBatch(batch);
        assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM ping_sample WHERE collector_id='retry'",
                Integer.class
            )
        ).isEqualTo(2);
        assertThat(
            jdbc.queryForObject(
                "SELECT measured_at FROM ping_sample WHERE collector_id='retry' AND attempt_id=1",
                Long.class
            )
        ).isEqualTo(at / 1000000);
        store.saveBatch(batch.toBuilder().setBatchId(2).build());
        assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM ping_sample WHERE collector_id='retry'",
                Integer.class
            )
        ).isEqualTo(2);
    }

    @Test
    void collectionFailuresNeverBecomePacketLoss() {
        long at = System.currentTimeMillis() * 1000000;
        var b = Batch.newBuilder()
            .setCollectorId("errors")
            .setBatchId(1)
            .addResults(result("errors", 1, Status.SEND_ERROR, at))
            .addResults(result("errors", 2, Status.SKIPPED, at))
            .addResults(result("errors", 3, Status.COLLECTION_ERROR, at))
            .build();
        store.saveBatch(b);
        var errors = jdbc.queryForList(
            "SELECT error FROM ping_sample WHERE collector_id='errors'",
            String.class
        );
        assertThat(errors).hasSize(3);
        for (String error : errors)
            assertThat(
                new PingSample(1, "h", "127.0.0.1", at / 1000000, null, error).outcome()
            ).isEqualTo(PingSample.ERROR);
    }

    @Test
    void invalidBatchDoesNotCommitPartialRowsOrReceipt() {
        var b = Batch.newBuilder()
            .setCollectorId("invalid")
            .setBatchId(1)
            .addResults(result("invalid", 1, Status.SUCCESS, System.currentTimeMillis() * 1000000))
            .addResults(result("wrong", 2, Status.SUCCESS, 1))
            .build();
        assertThatThrownBy(() -> store.saveBatch(b)).isInstanceOf(IllegalArgumentException.class);
        assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM collector_batch WHERE collector_id='invalid'",
                Integer.class
            )
        ).isZero();
    }

    @Test
    void replayKeepsThresholdCapturedByCollector() {
        var r = result("policy", 1, Status.SUCCESS, System.currentTimeMillis() * 1000000)
            .toBuilder()
            .setLatencyLimitMs(35)
            .build();
        var b = Batch.newBuilder().setCollectorId("policy").setBatchId(1).addResults(r).build();
        store.saveBatch(b);
        store.saveBatch(b);
        assertThat(
            jdbc.queryForObject(
                "SELECT latency_limit FROM ping_sample WHERE collector_id='policy'",
                Double.class
            )
        ).isEqualTo(35);
    }

    @Test
    void correlatedIcmpErrorsAreNetworkLoss() {
        var b = Batch.newBuilder()
            .setCollectorId("icmp-error")
            .setBatchId(1)
            .addResults(
                result("icmp-error", 1, Status.ICMP_ERROR, System.currentTimeMillis() * 1000000)
            )
            .build();
        store.saveBatch(b);
        String error = jdbc.queryForObject(
            "SELECT error FROM ping_sample WHERE collector_id='icmp-error'",
            String.class
        );
        assertThat(new PingSample(1, "h", "127.0.0.1", 0, null, error).outcome()).isEqualTo(
            PingSample.LOSS
        );
    }
}
