package br.com.scriptping;

import br.com.scriptping.ingestion.SampleIngestionService;
import br.com.scriptping.collector.proto.*;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = {
    "collector.enabled=false", "indicators.aggregation.initial-delay-ms=3600000",
    "spring.datasource.url=jdbc:h2:mem:collector;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "zabbix.url=", "zabbix.username=", "zabbix.password=",
})
class CollectorPersistenceTests {
    @Autowired SampleIngestionService store;
    @Autowired JdbcTemplate jdbc;

    private Result result(String host, long attempt, Status status, long at, double latency) {
        return Result.newBuilder().setCollectorId(host).setHostId(host).setTarget("127.0.0.1")
            .setAttemptId(attempt).setSequence(attempt).setScheduledAt(at * 1000000)
            .setRttNs((long)(latency * 1000000)).setStatus(status).setLatencyLimitMs(20).build();
    }
    private Batch batch(String id, long number, Result... results) {
        return Batch.newBuilder().setCollectorId(id).setBatchId(number).setProtocolVersion(1)
            .addAllResults(java.util.List.of(results)).build();
    }
    private long count(String field, String host) {
        return jdbc.queryForObject("SELECT COALESCE(SUM("+field+"),0) FROM ping_minute WHERE host_id=?",Long.class,host);
    }
    @Test void aggregatesAtOriginalMinuteAndDeduplicatesReplay() {
        long minute = Math.floorDiv(System.currentTimeMillis()-120000,60000)*60000;
        var b=batch("retry",1,result("retry",1,Status.SUCCESS,minute+1000,10),
            result("retry",2,Status.TIMEOUT,minute+2000,0),
            result("retry",3,Status.SUCCESS,minute+61000,30));
        store.saveBatch(b); store.saveBatch(b);
        assertThat(count("total_pings","retry")).isEqualTo(3);
        assertThat(count("healthy","retry")).isEqualTo(1);
        assertThat(count("degraded","retry")).isEqualTo(1);
        assertThat(count("lost","retry")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ping_sample WHERE collector_id='retry'",Long.class)).isZero();
        assertThat(jdbc.queryForList("SELECT minute_at FROM ping_minute WHERE host_id='retry' ORDER BY minute_at",Long.class))
            .containsExactly(minute,minute+60000);
        assertThat(jdbc.queryForObject("SELECT SUM(total_pings) FROM ping_rollup WHERE host_id='retry' AND resolution=3600000",Long.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT SUM(healthy+degraded+lost) FROM availability_change WHERE host_id='retry'",Long.class)).isEqualTo(3);
    }
    @Test void mergesBatchesAndPreservesExtremesAndThreshold() {
        long at=Math.floorDiv(System.currentTimeMillis(),60000)*60000;
        var r=result("merge",1,Status.SUCCESS,at,30).toBuilder().setLatencyLimitMs(35).build();
        store.saveBatch(batch("merge",1,r));
        store.saveBatch(batch("merge",2,result("merge",2,Status.SUCCESS,at+1,5)));
        assertThat(count("healthy","merge")).isEqualTo(2);
        assertThat(count("degraded","merge")).isZero();
        var row=jdbc.queryForMap("SELECT latency_min,latency_max,latency_sum FROM ping_minute WHERE host_id='merge'");
        assertThat(((Number)row.get("latency_min")).doubleValue()).isEqualTo(5);
        assertThat(((Number)row.get("latency_max")).doubleValue()).isEqualTo(30);
        assertThat(((Number)row.get("latency_sum")).doubleValue()).isEqualTo(35);
    }
    @Test void distinguishesNetworkLossFromCollectorErrors() {
        long at=System.currentTimeMillis();
        store.saveBatch(batch("errors",1,result("errors",1,Status.SEND_ERROR,at,0),
            result("errors",2,Status.SKIPPED,at,0), result("errors",3,Status.COLLECTION_ERROR,at,0),
            result("errors",4,Status.ICMP_ERROR,at,0)));
        assertThat(count("errors","errors")).isEqualTo(3);
        assertThat(count("lost","errors")).isEqualTo(1);
    }
    @Test void invalidBatchRollsBackAndCanBeTerminallyRejected() {
        var b=batch("invalid",1,result("invalid",1,Status.SUCCESS,System.currentTimeMillis(),10),
            result("wrong",2,Status.SUCCESS,1,10));
        assertThatThrownBy(()->store.saveBatch(b)).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("total_pings","invalid")).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM collector_batch WHERE collector_id='invalid'",Long.class)).isZero();
        store.rejectBatch(b,"Invalid result"); store.saveBatch(b);
        assertThat(jdbc.queryForObject("SELECT rejection FROM collector_batch WHERE collector_id='invalid'",String.class)).isEqualTo("Invalid result");
        assertThat(count("total_pings","invalid")).isZero();
    }
    @Test void expiredReplayDoesNotResurrectHistory() {
        store.saveBatch(batch("expired",1,result("expired",1,Status.SUCCESS,
            System.currentTimeMillis()-java.time.Duration.ofHours(49).toMillis(),10)));
        assertThat(count("total_pings","expired")).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM collector_batch WHERE collector_id='expired'",Long.class)).isEqualTo(1);
    }
    @Test void drainsLegacyHistoryBeforeDirectIngestionWithoutDoubleCounting() {
        long at=System.currentTimeMillis()-1000;
        jdbc.update("INSERT INTO ping_sample(host_id,target,measured_at,latency) VALUES('legacy','127.0.0.1',?,10)",at);
        store.saveBatch(batch("legacy",1,result("legacy",2,Status.TIMEOUT,at,0)));
        assertThat(count("total_pings","legacy")).isEqualTo(2);
        store.saveBatch(batch("legacy",2,result("legacy",3,Status.SUCCESS,at,5)));
        assertThat(count("total_pings","legacy")).isEqualTo(3);
    }
    @Test void aggregatesTenThousandHostsAcrossMultipleBatches() {
        long at = Math.floorDiv(System.currentTimeMillis(),60000)*60000;
        long started = System.nanoTime();
        for (int round=0;round<2;round++) for (int group=0;group<20;group++) {
            var builder=Batch.newBuilder().setCollectorId("load").setBatchId(round*20+group+1).setProtocolVersion(1);
            for (int i=0;i<500;i++) {
                int host=group*500+i;
                builder.addResults(result("load",round*10000+host+1,Status.SUCCESS,at+round*1000,10)
                    .toBuilder().setHostId("load-"+host));
            }
            store.saveBatch(builder.build());
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ping_minute WHERE host_id LIKE 'load-%'",Long.class)).isEqualTo(10000);
        assertThat(jdbc.queryForObject("SELECT SUM(total_pings) FROM ping_minute WHERE host_id LIKE 'load-%'",Long.class)).isEqualTo(20000);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ping_sample WHERE collector_id='load'",Long.class)).isZero();
        System.out.printf("DIRECT_INGESTION_SYNTHETIC results=20000 hosts=10000 elapsed_ms=%d%n",(System.nanoTime()-started)/1000000);
    }

    @Test void receiptFailureRollsBackSummariesRollupsAndRevision() {
        long cursor=jdbc.queryForObject("SELECT cursor_id FROM ping_aggregation_progress WHERE id=1",Long.class);
        jdbc.execute("ALTER TABLE collector_batch ADD CONSTRAINT reject_test_receipt CHECK (collector_id <> 'rollback')");
        try {
            assertThatThrownBy(()->store.saveBatch(batch("rollback",1,
                result("rollback",1,Status.SUCCESS,System.currentTimeMillis(),10))))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(count("total_pings","rollback")).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ping_rollup WHERE host_id='rollback'",Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM availability_change WHERE host_id='rollback'",Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT cursor_id FROM ping_aggregation_progress WHERE id=1",Long.class)).isEqualTo(cursor);
        } finally {jdbc.execute("ALTER TABLE collector_batch DROP CONSTRAINT reject_test_receipt");}
    }

}
