package br.com.scriptping.backup;

import br.com.scriptping.scheduling.ScheduledTask.Detail;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
class BackupJobs {
    private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger(BackupJobs.class);
    private final BackupStore store;
    private final BackupCapture capture;
    private final java.util.concurrent.ExecutorService worker =
        Executors.newSingleThreadExecutor(Thread.ofVirtual().name("equipment-backup").factory());
    private volatile Status status = new Status(false, 0, 0, 0, List.of(), null, List.of());

    record Failure(String host, String message) {}
    record Event(long timestamp, String message) {}
    record Status(boolean running, int total, int completed, int saved, List<Failure> failures,
                  String runId, List<Event> events) {}

    BackupJobs(BackupStore store, BackupCapture capture) {
        this.store = store;
        this.capture = capture;
    }

    Status status() {
        return status;
    }

    synchronized Status start(String hostId) {
        requireIdle();
        List<BackupStore.HostOption> targets;
        if (hostId == null) {
            targets = configuredTargets();
            if (targets.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Habilite o backup na configuração dos hosts.");
            }
        } else {
            var target = store.inventory().stream().filter(host -> host.id().equals(hostId)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Host não encontrado."));
            if (!target.ready()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Configure e valide a autenticação do host.");
            }
            targets = List.of(target);
        }
        String runId = hostId == null ? null : java.util.UUID.randomUUID().toString();
        var events = new ArrayList<Event>();
        if (runId != null) events.add(new Event(System.currentTimeMillis(), "Iniciando backup"));
        var started = new Status(true, targets.size(), 0, 0, List.of(), runId, List.copyOf(events));
        status = started;
        try {
            worker.execute(() -> {
                try {
                    run(targets, runId, events, detail -> {});
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ex) {
            status = new Status(false, targets.size(), 0, 0, List.of(), runId, List.copyOf(events));
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Executor de backup indisponível.", ex);
        }
        return started;
    }

    Status runScheduled(Consumer<Detail> report) throws InterruptedException {
        List<BackupStore.HostOption> targets;
        synchronized (this) {
            requireIdle();
            targets = configuredTargets();
            if (targets.isEmpty()) return new Status(false, 0, 0, 0, List.of(), null, List.of());
            status = new Status(true, targets.size(), 0, 0, List.of(), null, List.of());
        }
        return run(targets, null, new ArrayList<>(), report);
    }

    private void requireIdle() {
        if (status.running()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Já existe um backup em execução.");
        }
    }

    private List<BackupStore.HostOption> configuredTargets() {
        return store.inventory().stream().filter(BackupStore.HostOption::backupEnabled).toList();
    }

    private Status run(List<BackupStore.HostOption> targets, String runId, List<Event> events,
                       Consumer<Detail> report) throws InterruptedException {
        Consumer<String> progress = message -> {
            if (runId == null) return;
            events.add(new Event(System.currentTimeMillis(), message));
            var current = status;
            status = new Status(true, current.total(), current.completed(), current.saved(), current.failures(),
                runId, List.copyOf(events));
        };
        int completed = 0;
        int saved = 0;
        var failures = new ArrayList<Failure>();
        Status finished;
        try {
            for (var target : targets) {
                report.accept(new Detail(target.id(), target.name(), "PENDING", "Aguardando backup."));
            }
            for (var target : targets) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                report.accept(new Detail(target.id(), target.name(), "RUNNING", "Coletando configuração."));
                String failure = null;
                try {
                    if (!target.ready()) throw new IOException("Configure e valide a autenticação do host.");
                    var result = capture.capture(target.id(), progress);
                    progress.accept("Salvando backup");
                    store.insert(target.id(), result);
                    progress.accept("Backup concluído com sucesso");
                    saved++;
                } catch (InterruptedException ex) {
                    progress.accept("Backup interrompido");
                    throw ex;
                } catch (Exception ex) {
                    LOGGER.warn("Backup failed for host {} ({})", target.id(), ex.getClass().getSimpleName());
                    failure = ex.getClass() == IOException.class && ex.getMessage() != null
                        ? ex.getMessage() : "Falha ao coletar ou armazenar a configuração.";
                    failures.add(new Failure(target.name(), failure));
                    progress.accept("Erro: " + failure);
                }
                status = new Status(true, targets.size(), ++completed, saved, List.copyOf(failures), runId, List.copyOf(events));
                report.accept(new Detail(target.id(), target.name(), failure == null ? "COMPLETED" : "FAILED",
                    failure == null ? "Backup salvo." : failure));
            }
        } finally {
            finished = new Status(false, targets.size(), completed, saved, List.copyOf(failures), runId, List.copyOf(events));
            status = finished;
        }
        return finished;
    }

    @PreDestroy
    void close() {
        worker.shutdownNow();
    }
}
