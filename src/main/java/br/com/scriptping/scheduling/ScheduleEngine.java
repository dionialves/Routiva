package br.com.scriptping.scheduling;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

/** One application instance owns execution. Database claims serialize concurrent dispatcher calls. */
@Component
public class ScheduleEngine {
    private final ScheduleStore store;
    private final TaskCatalog catalog;
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
        new SynchronousQueue<>(), Thread.ofPlatform().name("scheduled-task-", 0).factory());
    private volatile boolean stopping;
    public ScheduleEngine(ScheduleStore store, TaskCatalog catalog) { this.store = store; this.catalog = catalog; }
    @PostConstruct public void recover() { store.recover(System.currentTimeMillis()); }
    @Scheduled(fixedDelay = 1000, scheduler = "scheduledTaskDispatcher")
    public synchronized void dispatch() {
        if (stopping) return;
        for (var claim : store.claim(System.currentTimeMillis(), 2 - workers.getActiveCount())) {
            try { workers.execute(() -> execute(claim)); }
            catch (RejectedExecutionException e) {
                store.finish(claim.executionId(), "SKIPPED", "Ignorada: executor indisponível.", System.currentTimeMillis());
            }
        }
    }
    public synchronized String runNow(String id) {
        if (stopping) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "Executor em encerramento.");
        var claim = store.claimNow(id, System.currentTimeMillis(), 2 - workers.getActiveCount());
        try { workers.execute(() -> execute(claim)); }
        catch (RejectedExecutionException error) {
            store.finish(claim.executionId(), "SKIPPED", "Executor indisponível.", System.currentTimeMillis());
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "Executor ocupado. Tente novamente.");
        }
        return claim.executionId();
    }
    private void execute(ScheduleStore.Claim claim) {
        String state, message;
        try {
            var task = catalog.require(claim.entry().taskKey());
            task.validate(claim.entry().parameters());
            var outcome = task.execute(claim.entry().parameters(), new ScheduledTask.Context(claim.executionId(),
                detail -> store.detail(claim.executionId(), detail, System.currentTimeMillis())));
            state = outcome.partial() ? "PARTIAL" : "COMPLETED";
            message = outcome.message() == null ? "Execução concluída." : outcome.message();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); state = "INTERRUPTED"; message = "Execução interrompida pelo encerramento da aplicação.";
        } catch (Exception e) {
            state = "FAILED"; message = "A tarefa falhou. Consulte os logs da aplicação para detalhes.";
            LoggerFactory.getLogger(getClass()).error("Scheduled execution {} failed", claim.executionId(), e);
        }
        store.finish(claim.executionId(), state, message.substring(0, Math.min(message.length(), 4000)), System.currentTimeMillis());
    }
    @PreDestroy public synchronized void close() {
        stopping = true;
        workers.shutdownNow();
        try { workers.awaitTermination(10, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
    @Configuration
    static class DispatcherConfiguration {
        @Bean ThreadPoolTaskScheduler scheduledTaskDispatcher() {
            var scheduler = new ThreadPoolTaskScheduler();
            scheduler.setPoolSize(1); scheduler.setThreadNamePrefix("schedule-dispatch-");
            return scheduler;
        }
    }
}
