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
    void backgroundRefreshAndRestartRestoreIndicatorsFromSavedPings() {
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
        assertThat(
            rules.windowQuality(
                afterRestart.stats().lossesFiveMinutes(),
                afterRestart.stats().attemptsFiveMinutes()
            )
        ).isEqualTo(Quality.CRITICO);
        assertThat(afterRestart.stats().lossesLastHour()).isEqualTo(3);
        assertThat(afterRestart.consecutive()).isEqualTo(3);
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
}
