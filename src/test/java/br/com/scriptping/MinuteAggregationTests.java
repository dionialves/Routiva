package br.com.scriptping;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import br.com.scriptping.aggregation.MinuteAggregationService;
import br.com.scriptping.aggregation.MinuteAggregationRepository;
import br.com.scriptping.aggregation.MinuteAggregationScheduled;

class MinuteAggregationTests extends MinuteTestSupport {
    @Test void splitsOneMinuteAcrossBatchesAndUsesHistoricalLatencyLimits() {
        insert(end-59000,10d,null);insert(end-40000,30d,null);
        insert(end-30000,null,"Tempo esgotado");insert(end-20000,null,"Coletor: SEND_ERROR");
        insert(host,end-10000,30d,null,40);
        drain(2);
        assertThat(count("total_pings")).isEqualTo(5);
        assertThat(count("healthy")).isEqualTo(2);
        assertThat(count("degraded")).isEqualTo(1);
        assertThat(count("lost")).isEqualTo(1);
        assertThat(count("errors")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ping_minute",Long.class)).isEqualTo(1);
        var m=summaries.totals(end-60000,end,List.of(host)).values().iterator().next().metrics();
        assertThat(m.latency()).isEqualTo(70d/3);
        assertThat(m.totalPings()).isEqualTo(5);
        assertThat(m.technical()).isEqualTo(75d);
        assertThat(m.operational()).isEqualTo(50d);
        drain(2);assertThat(count("total_pings")).isEqualTo(5);
    }
    @Test void restartAndSixHourBacklogDoNotSkipOldPingsOrWaitBetweenPages() {
        for(int minute=0;minute<360;minute++)insert(end-360*60000L+minute*60000L,10d,null);
        long b=boundary();
        summaries.aggregate(b,7,now);
        assertThat(summaries.progress().completeThrough()).isZero();
        summaries=new MinuteAggregationService(new MinuteAggregationRepository(jdbc,manager));
        drain(7);
        assertThat(count("total_pings")).isEqualTo(360);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ping_minute",Long.class)).isEqualTo(360);
        assertThat(summaries.progress().cursor()).isEqualTo(b);
        assertThat(summaries.progress().completeThrough()).isEqualTo(end);
        insert(end-360*60000L+1000,null,"Sem resposta ICMP"); // Late arrival updates the oldest minute.
        drain(7);
        assertThat(count("total_pings")).isEqualTo(361);
        assertThat(count("lost")).isEqualTo(1);
    }
    @Test void summaryAndCursorRollbackTogetherThenRetryExactlyOnce() {
        insert(end-10000,10d,null);long b=boundary();
        jdbc.execute("ALTER TABLE ping_aggregation_progress ADD CONSTRAINT fail_progress CHECK(cursor_id=0)");
        assertThatThrownBy(()->summaries.aggregate(b,10,now)).isInstanceOf(RuntimeException.class);
        assertThat(count("total_pings")).isZero();assertThat(summaries.progress().cursor()).isZero();
        jdbc.execute("ALTER TABLE ping_aggregation_progress DROP CONSTRAINT fail_progress");
        drain(10);drain(10);
        assertThat(count("total_pings")).isEqualTo(1);
    }
    @Test void concurrentWorkersSerializeWithoutDoubleCounting() throws Exception {
        for(int i=0;i<50;i++)insert(end-10000,10d,null);
        long b=boundary();
        try(var pool=Executors.newFixedThreadPool(2)) {
            var jobs=pool.invokeAll(List.of(()->summaries.aggregate(b,100,now),()->summaries.aggregate(b,100,now)));
            for(var job:jobs)job.get();
        }
        assertThat(count("total_pings")).isEqualTo(50);
    }
    @Test void idGapsAndDeletionAfterAggregationDoNotResetProgress() {
        jdbc.execute("ALTER TABLE ping_sample ALTER COLUMN id RESTART WITH 1000000");
        insert(end-10000,10d,null);drain(2);
        assertThat(summaries.progress().cursor()).isEqualTo(1000000);
        jdbc.update("DELETE FROM ping_sample");
        assertThat(boundary()).isEqualTo(1000000);
        drain(2);assertThat(count("total_pings")).isEqualTo(1);
    }
    @Test void lateArrivalAndIpChangeKeepSeparateSummaries() {
        insert(end-60000,10d,null);drain(10);
        var changed=new Host(host.id(),host.name(),"127.0.0.2",List.of());
        insert(changed,end-60000,null,"Tempo esgotado",20);drain(10);
        assertThat(summaries.totals(end-60000,end,List.of(host)).values().iterator().next().lost()).isZero();
        assertThat(summaries.totals(end-60000,end,List.of(changed)).values().iterator().next().lost()).isEqualTo(1);
    }
    @Test void pendingRowsSurviveRetentionUntilAggregated() {
        long old=System.currentTimeMillis()-49*3600000L;
        insert(old,10d,null);
        samples.cleanup();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ping_sample",Long.class)).isEqualTo(1);
        drain(10);samples.cleanup();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ping_sample",Long.class)).isZero();
    }
    @Test void nonFiniteAndNegativeLatenciesFollowRawOutcomeSemantics() {
        insert(end-1000,Double.NaN,null);
        insert(end-1000,Double.POSITIVE_INFINITY,null);
        insert(end-1000,-1d,"Tempo esgotado");
        insert(end-1000,0d,null);
        drain(10);
        assertThat(count("healthy")).isEqualTo(1);
        assertThat(count("lost")).isEqualTo(1);
        assertThat(count("errors")).isEqualTo(2);
        assertThat(summaries.totals(end-60000,end,List.of(host)).values().iterator().next().sum()).isZero();
    }
    @Test void olderConcurrentBoundaryCannotMoveProgressOrConsolidationBackwards() {
        insert(end-10000,10d,null);long older=boundary();
        insert(end-1000,20d,null);drain(1);
        var before=summaries.progress();
        summaries.aggregate(older,1,now-60000);
        assertThat(summaries.progress()).isEqualTo(before);
        assertThat(count("total_pings")).isEqualTo(2);
    }
    @Test void failureUpdatingExistingMinuteDoesNotDuplicatePreviousContribution() {
        insert(end-10000,10d,null);drain(1);long previous=summaries.progress().cursor();
        insert(end-5000,null,"Tempo esgotado");
        jdbc.execute("ALTER TABLE ping_aggregation_progress ADD CONSTRAINT fail_update CHECK(cursor_id="+previous+")");
        assertThatThrownBy(()->summaries.aggregate(boundary(),1,now)).isInstanceOf(RuntimeException.class);
        assertThat(count("total_pings")).isEqualTo(1);
        assertThat(count("lost")).isZero();
        jdbc.execute("ALTER TABLE ping_aggregation_progress DROP CONSTRAINT fail_update");
        drain(1);drain(1);
        assertThat(count("total_pings")).isEqualTo(2);assertThat(count("lost")).isEqualTo(1);
    }
    @Test void schemaUpgradeRemovesRetiredTablesAndPreservesActiveDataOnRepeatedStarts() {
        jdbc.execute("CREATE TABLE analytics_checkpoint_guard (id INTEGER PRIMARY KEY)");
        jdbc.execute("CREATE TABLE analytics_checkpoint (id VARCHAR(36) PRIMARY KEY)");
        jdbc.execute("CREATE TABLE analytics_checkpoint_block (hash VARCHAR(64) PRIMARY KEY)");
        jdbc.execute("CREATE TABLE analytics_checkpoint_part (checkpoint_id VARCHAR(36) REFERENCES analytics_checkpoint(id), block_hash VARCHAR(64) REFERENCES analytics_checkpoint_block(hash))");
        jdbc.execute("CREATE TABLE host_latency_policy (host_id VARCHAR(255) PRIMARY KEY, latency_limit DOUBLE PRECISION NOT NULL)");
        jdbc.update("INSERT INTO analytics_checkpoint_guard VALUES (1)");
        jdbc.update("INSERT INTO analytics_checkpoint VALUES ('old-checkpoint')");
        jdbc.update("INSERT INTO analytics_checkpoint_block VALUES ('old-block')");
        jdbc.update("INSERT INTO analytics_checkpoint_part VALUES ('old-checkpoint','old-block')");
        jdbc.update("INSERT INTO host_latency_policy VALUES ('a',20)");
        insert(end-10000,10d,null);drain(1);
        jdbc.update("INSERT INTO collector_batch VALUES ('collector',1,?)",now);
        jdbc.update("INSERT INTO zabbix_settings VALUES (1,'saved-settings')");
        var progress=summaries.progress();
        var schema=new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("schema.sql"));
        schema.execute(ds);schema.execute(ds);
        for(String name:List.of("analytics_checkpoint_part","analytics_checkpoint","analytics_checkpoint_block","analytics_checkpoint_guard","host_latency_policy")) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE LOWER(table_name)=?",Long.class,name)).as(name).isZero();
        }
        assertThat(count("total_pings")).isEqualTo(1);
        assertThat(summaries.progress()).isEqualTo(progress);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ping_sample",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM collector_batch",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT payload FROM zabbix_settings WHERE id=1",String.class)).isEqualTo("saved-settings");
        assertThat(jdbc.queryForObject("SELECT id FROM ping_ingestion_guard",Integer.class)).isEqualTo(1);
        drain(1);assertThat(count("total_pings")).isEqualTo(1);
    }
    @Test void scheduledWorkerDrainsAllPagesAndLogsCommittedProgress() {
        for(int i=0;i<10;i++)insert(end-60000,10d,null);
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger("routiva.indicators");
        var appender=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();logger.addAppender(appender);
        try {
            new MinuteAggregationScheduled(samples,summaries,3).aggregate();
            assertThat(count("total_pings")).isEqualTo(10);
            assertThat(appender.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)).anyMatch(s->s.contains("MINUTE_AGGREGATION_COMPLETED"));
        } finally {logger.detachAppender(appender);}
    }
}
