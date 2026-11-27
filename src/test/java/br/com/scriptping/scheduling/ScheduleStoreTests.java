package br.com.scriptping.scheduling;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import static org.assertj.core.api.Assertions.*;

class ScheduleStoreTests {
    ScheduleStore store;
    JdbcTemplate jdbc;
    final long now = Instant.parse("2026-11-16T05:00:00Z").toEpochMilli();
    @BeforeEach void database() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:scheduler-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        var schema = new ResourceDatabasePopulator(new ClassPathResource("schema.sql"));
        schema.execute(source); schema.execute(source); // Idempotent startup migration.
        jdbc = new JdbcTemplate(source);
        store = new ScheduleStore(jdbc, new ObjectMapper(), new DataSourceTransactionManager(source));
    }
    ScheduleStore.Entry entry(String id, long due) {
        return new ScheduleStore.Entry(id, "Avaliação", id.equals("one") ? "test" : id, "Teste", new ScheduleRule("DAILY", "02:00", List.of(), 1, 30, "America/Sao_Paulo"), Map.of(), true, due);
    }
    @Test void claimsOnceAndRetainsHistoryAfterDeletion() {
        store.save(entry("one", now), true, now);
        var claimed = store.claim(now, 2);
        assertThat(claimed).hasSize(1);
        assertThat(store.claim(now, 2)).isEmpty();
        store.finish(claimed.getFirst().executionId(), "COMPLETED", "OK", now + 5000);
        store.change("one", "delete", now + 6000);
        assertThat(store.all()).isEmpty();
        assertThat(store.history(0)).singleElement().satisfies(e -> {
            assertThat(e.state()).isEqualTo("COMPLETED");
            assertThat(e.scheduleName()).isEqualTo("Avaliação");
        });
    }
    @Test void historyFiltersBeforePaginatingAndKeepsDeletedScheduleResults() {
        store.save(entry("one", now), true, now);
        store.save(entry("two", now), true, now);
        for (int day = 0; day < 26; day++) store.claim(now + day * 86400000L, 2);
        assertThat(store.historyCount("one")).isEqualTo(26);
        assertThat(store.history("one", 0)).hasSize(25).allMatch(e -> e.scheduleId().equals("one"));
        assertThat(store.history("one", 1)).hasSize(1);
        assertThat(store.historySummary("one", 1, 10, "scheduled_at", false)).hasSize(10)
            .first().extracting(ScheduleStore.Execution::scheduledAt).isEqualTo(now + 10 * 86400000L);
        store.change("one", "delete", now);
        assertThat(store.historyCount("one")).isEqualTo(26);
    }
    @Test void oneActivityOwnsOneScheduleAndApProgressSurvivesRestart() {
        store.save(entry("one", now), true, now);
        var duplicate = entry("two", now);
        assertThatThrownBy(() -> store.save(new ScheduleStore.Entry(duplicate.id(), duplicate.name(), "test", duplicate.taskLabel(),
            duplicate.rule(), duplicate.parameters(), false, duplicate.nextRun()), true, now)).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        var claims = store.claim(now, 2);
        assertThat(claims).hasSize(1);
        String execution = claims.getFirst().executionId();
        store.detail(execution, new ScheduledTask.Detail("ap", "AP", "RUNNING", "Consultando"), now);
        store.detail(execution, new ScheduledTask.Detail("ap", "AP", "RUNNING", "Medindo"), now + 1000);
        assertThat(store.details(store.history(0)).get(execution)).singleElement()
            .extracting(ScheduledTask.Detail::message).isEqualTo("Medindo");
        store.finish(execution, "INTERRUPTED", "Interrompida", now + 2000);
        assertThat(store.details(store.history(0)).get(execution)).singleElement()
            .extracting(ScheduledTask.Detail::state).isEqualTo("INTERRUPTED");
        store.detail(execution, new ScheduledTask.Detail("other", "Outro AP", "PENDING", "Aguardando"), now + 3000);
        store.recover(now + 4000);
        assertThat(store.details(store.history(0)).get(execution)).allMatch(d -> d.state().equals("INTERRUPTED"));
    }
    @Test void runNowWorksWhenPausedAndDoesNotMoveTheNextOccurrence() {
        store.save(entry("one", now + 86400000), true, now);
        store.change("one", "pause", now);
        long next = store.all().getFirst().nextRun();
        var claim = store.claimNow("one", now, 2);
        assertThat(claim.entry().enabled()).isFalse();
        assertThat(store.all().getFirst().nextRun()).isEqualTo(next);
        assertThat(store.history(0)).singleElement().satisfies(e -> {
            assertThat(e.message()).contains("manual"); assertThat(e.state()).isEqualTo("RUNNING");
        });
        assertThatThrownBy(() -> store.claimNow("one", now, 2))
            .isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class,
                e -> assertThat(e.getStatusCode().value()).isEqualTo(409));
        assertThat(store.historyCount()).isEqualTo(1);
    }
    @Test void runNowRejectsCapacityAndDeletedSchedulesWithoutRecordingAnExecution() {
        store.save(entry("one", now + 86400000), true, now);
        assertThatThrownBy(() -> store.claimNow("one", now, 0)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(store.historyCount()).isZero();
        store.change("one", "delete", now);
        assertThatThrownBy(() -> store.claimNow("one", now, 2)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(store.historyCount()).isZero();
    }
    @Test void legacyDuplicatesAreConsolidatedAndTheirHistoryIsPreservedIdempotently() {
        store.save(entry("one", now), true, now);
        var execution = store.claim(now, 2).getFirst().executionId();
        store.detail(execution, new ScheduledTask.Detail("ap", "AP", "COMPLETED", "OK"), now);
        jdbc.update("DROP INDEX task_schedule_activity_unique");
        jdbc.update("INSERT INTO task_schedule(id,name,task_key,task_label,rule_json,parameters_json,enabled,next_run,deleted,created_at,updated_at) SELECT 'legacy','Outro','test',task_label,rule_json,parameters_json,FALSE,next_run,FALSE,created_at,? FROM task_schedule WHERE id='one'", now + 1000);
        var migration = new ResourceDatabasePopulator(new ClassPathResource("schema.sql"));
        migration.execute(jdbc.getDataSource()); migration.execute(jdbc.getDataSource());
        assertThat(store.all()).singleElement().extracting(ScheduleStore.Entry::id).isEqualTo("legacy");
        assertThat(store.historyCount("legacy")).isEqualTo(1);
        assertThat(store.details(store.history("legacy", 0)).get(execution)).hasSize(1);
        assertThatThrownBy(() -> store.save(entry("one", now), false, now)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
    @Test void manualEngineExecutionUsesTheSameWorkerAndHistory() throws Exception {
        var finished = new CountDownLatch(1);
        var task = new ScheduledTask() {
            public String key() { return "test"; }
            public String label() { return "Teste"; }
            public Outcome execute(Map<String, String> parameters) { finished.countDown(); return Outcome.completed("Manual OK"); }
        };
        store.save(entry("one", now + 86400000), true, now);
        var engine = new ScheduleEngine(store, new TaskCatalog(List.of(task)));
        String execution;
        try { execution = engine.runNow("one"); assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue(); }
        finally { engine.close(); }
        assertThat(store.history(0)).singleElement().satisfies(e -> {
            assertThat(e.id()).isEqualTo(execution); assertThat(e.state()).isEqualTo("COMPLETED");
        });
    }
    @Test void downtimeSkipsBacklogWithoutStartingTask() {
        store.save(entry("one", now - 86400000), true, now);
        assertThat(store.claim(now, 2)).isEmpty();
        assertThat(store.history(0)).singleElement().extracting(ScheduleStore.Execution::state).isEqualTo("MISSED");
        assertThat(store.all().getFirst().nextRun()).isGreaterThan(now);
    }
    @Test void overlappingOccurrencesAreSkippedAndCapacityIsBounded() {
        store.save(entry("one", now), true, now);
        assertThat(store.claim(now, 1)).hasSize(1);
        long next = store.all().getFirst().nextRun();
        assertThat(store.claim(next, 1)).isEmpty();
        assertThat(store.history(0).getFirst().state()).isEqualTo("SKIPPED");
        store.save(entry("two", next), true, now);
        assertThat(store.claim(next, 0)).isEmpty();
        assertThat(store.historyCount()).isEqualTo(3);
    }
    @Test void restartRecoversInterruptedExecutionsAndPauseResumesOnlyFuture() {
        store.save(entry("one", now), true, now);
        store.claim(now, 2);
        store.recover(now + 1000);
        assertThat(store.history(0).getFirst().state()).isEqualTo("INTERRUPTED");
        store.change("one", "pause", now);
        assertThat(store.claim(now + 86400000, 2)).isEmpty();
        store.change("one", "enable", now + 86400000);
        assertThat(store.all().getFirst().nextRun()).isGreaterThan(now + 86400000);
    }
    @Test void simultaneousDispatchersCannotClaimSameOccurrence() throws Exception {
        store.save(entry("one", now), true, now);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            Callable<Integer> run = () -> { gate.await(); return store.claim(now, 2).size(); };
            var first = executor.submit(run); var second = executor.submit(run); gate.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS) + second.get(5, TimeUnit.SECONDS)).isEqualTo(1);
        }
        assertThat(store.historyCount()).isEqualTo(1);
    }
    @Test void cronRecurrenceSkipsWhileAnImmediateRunIsActive() {
        var rule = ScheduleRule.cron("* * * * *", "UTC");
        store.save(new ScheduleStore.Entry("one", "Teste", "test", "Teste", rule, Map.of(), true, now), true, now);
        var manual = store.claimNow("one", now - 1000, 2);
        assertThat(store.claim(now, 2)).isEmpty();
        assertThat(store.history("one", 0).getFirst().state()).isEqualTo("SKIPPED");
        assertThat(store.all().getFirst().nextRun()).isEqualTo(now + 60_000);
        store.finish(manual.executionId(), "COMPLETED", "OK", now + 1000);
        assertThat(store.claim(now + 60_000, 2)).hasSize(1);
    }

    @Test void firstSaveAfterManualRunActivatesCronButEditingAnOldPausedRuleKeepsItPaused() {
        var rule = ScheduleRule.cron("0 1 * * *", "UTC");
        long next = rule.next(now);
        store.save(new ScheduleStore.Entry("one", "Teste", "test", "Teste", rule, Map.of(), false, next), true, now);
        var run = store.claimNow("one", now, 2);
        store.finish(run.executionId(), "COMPLETED", "OK", now + 1000);
        var save = new ScheduleStore.Entry("one", "Teste", "test", "Teste", rule, Map.of(), true, next);
        store.save(save, false, now);
        assertThat(store.all().getFirst().enabled()).isTrue();
        assertThat(store.all().getFirst().nextRun()).isEqualTo(next);
        assertThat(store.historyCount("one")).isEqualTo(1);

        store.save(entry("two", next), true, now);
        store.change("two", "pause", now);
        store.save(new ScheduleStore.Entry("two", "Teste", "two", "Teste", rule, Map.of(), true, next), false, now);
        assertThat(store.all().stream().filter(entry -> entry.id().equals("two")).findFirst().orElseThrow().enabled())
            .isFalse();
    }

    @Test void engineRunsRegisteredTaskWithoutSessionAndPersistsOutcome() throws Exception {
        var completed = new CountDownLatch(1);
        var task = new ScheduledTask() {
            public String key() { return "test"; }
            public String label() { return "Teste"; }
            public Outcome execute(Map<String, String> parameters) {
                completed.countDown(); return Outcome.completed("Executada sem navegador.");
            }
        };
        var engine = new ScheduleEngine(store, new TaskCatalog(List.of(task)));
        try {
            long current = System.currentTimeMillis();
            var rule = ScheduleRule.cron("* * * * *", "UTC");
            store.save(new ScheduleStore.Entry("one", "Teste", "test", "Teste", rule, Map.of(), true, current), true, current);
            engine.dispatch(); assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
        } finally { engine.close(); }
        assertThat(store.history(0).getFirst().state()).isEqualTo("COMPLETED");
        assertThat(store.history(0).getFirst().message()).contains("sem navegador");
    }
    @Test void failedAndPartialTasksPersistDistinctResults() throws Exception {
        for (boolean partial : List.of(false, true)) {
            var finished = new CountDownLatch(1);
            var task = new ScheduledTask() {
                public String key() { return "test"; }
                public String label() { return "Teste"; }
                public Outcome execute(Map<String, String> parameters) {
                    finished.countDown();
                    if (!partial) throw new IllegalStateException("unavailable");
                    return new Outcome(true, "Um item falhou.");
                }
            };
            long current = System.currentTimeMillis();
            String id = "one";
            jdbc.update("DELETE FROM task_schedule WHERE id=?", id);
            store.save(entry(id, current), true, current);
            var engine = new ScheduleEngine(store, new TaskCatalog(List.of(task)));
            try {
                engine.dispatch(); assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
            } finally { engine.close(); }
            assertThat(store.latest().get(id).state()).isEqualTo(partial ? "PARTIAL" : "FAILED");
        }
    }
}
