package br.com.scriptping;

import static br.com.scriptping.PacketLossAnalysis.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
    properties = {
        "spring.datasource.url=jdbc:h2:mem:quality-store;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "zabbix.url=",
        "zabbix.username=",
        "zabbix.password=",
        "monitor.interval-ms=1000",
    }
)
class PacketLossHistoryIntegrationTests {

    @Autowired
    SampleStore store;

    @Autowired
    PacketLossAnalysisService analysis;

    @Autowired
    PacketLossPolicy rules;

    @Autowired
    PlatformTransactionManager transactions;

    @Test
    void backgroundRefreshCollectsNewPingsAndRestartWithoutCheckpointStartsEmpty() {
        analysis.refresh();
        Host host = new Host("restore", "Restore", "10.7.0.1", List.of());
        store.save(host, new PingProbe.Result(null, "Tempo esgotado"));
        store.save(host, new PingProbe.Result(null, "Tempo esgotado"));
        store.save(host, new PingProbe.Result(null, "Tempo esgotado"));
        analysis.refresh(); // Repeated database reads must not duplicate losses.
        analysis.refresh();
        var live = analysis.current(List.of(host)).get(host.id());
        assertThat(live.stats().lossesLastMinute()).isEqualTo(3);
        assertThat(
            rules.windowQuality(
                live.stats().lossesFiveMinutes(),
                live.stats().attemptsFiveMinutes()
            )
        ).isEqualTo(Quality.CRITICO);
        var restored = new PacketLossAnalysisService(store, rules, 1000, 2000);
        restored.refresh();
        var afterRestart = restored
            .current(List.of(host))
            .get(host.id());
        assertThat(afterRestart.stats().attemptsFiveMinutes()).isZero();
        assertThat(afterRestart.stats().lossesLastHour()).isZero();
        restored.closeCheckpointWriter();
    }

    @Test
    void rolledBackSamplesCannotLeakIntoPresentationHistory() {
        Host host = new Host("rolled-back", "Rollback", "10.7.0.2", List.of());
        new TransactionTemplate(transactions).execute(status -> {
            store.save(host, new PingProbe.Result(null, "Tempo esgotado"));
            status.setRollbackOnly();
            return null;
        });
        analysis.refresh();
        var result = analysis.current(List.of(host)).get(host.id());
        assertThat(result.stats().attemptsLastMinute()).isZero();
        assertThat(result.level()).isEqualTo(Level.NORMAL);
    }
    @Test
    void cursorWaitsForPendingWritesAndDoesNotSkipTheirIds() throws Exception {
        Host host = new Host("concurrent", "Concurrent", "10.7.0.9", List.of());
        var written = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(3)) {
            var first = executor.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                var sample = store.save(host, new PingProbe.Result(12d, null));
                written.countDown();
                try {
                    if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
                } catch (InterruptedException ex) { throw new RuntimeException(ex); }
                return sample;
            }));
            try {
                org.assertj.core.api.Assertions.assertThat(written.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                var boundary = executor.submit(store::committedBoundary);
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> boundary.get(150, java.util.concurrent.TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
                var second = executor.submit(() -> store.save(host, new PingProbe.Result(14d, null)));
                release.countDown();
                var one = first.get(5, java.util.concurrent.TimeUnit.SECONDS);
                var two = second.get(5, java.util.concurrent.TimeUnit.SECONDS);
                assertThat(two.id()).isGreaterThan(one.id());
                assertThat(boundary.get(5, java.util.concurrent.TimeUnit.SECONDS)).isGreaterThanOrEqualTo(one.id());
                var loaded = new ArrayList<PingSample>();
                store.readIncrement(one.id() - 1, store.committedBoundary(), 0, loaded::add);
                assertThat(loaded).extracting(PingSample::id).contains(one.id(), two.id());
            } finally { release.countDown(); }
        }
    }

    @Test
    void indicatorLoggerWritesSeparateRollingFile() throws Exception {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("routiva.indicators");
        assertThat(logger.isAdditive()).isFalse();
        var appender = (ch.qos.logback.core.rolling.RollingFileAppender<?>) logger.getAppender("INDICATORS");
        assertThat(appender).isNotNull();
        assertThat(appender.isStarted()).isTrue();
        assertThat(appender.getRollingPolicy()).isInstanceOf(ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy.class);
        analysis.refresh();
        String contents = java.nio.file.Files.readString(java.nio.file.Path.of(appender.getFile()));
        assertThat(contents).contains("event=", "run_id=", "retained_pings=", "indicators_published=true");
    }

}
