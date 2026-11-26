package br.com.scriptping.backup;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
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
        if (status.running()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Já existe um backup em execução.");
        }
        List<BackupStore.HostOption> targets;
        if (hostId == null) {
            var selected = new java.util.HashSet<>(store.settings().hostIds());
            targets = store.inventory().stream().filter(host -> selected.contains(host.id())).toList();
            if (targets.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Selecione os hosts nas configurações de backup.");
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
        worker.execute(() -> run(targets, runId, events));
        return started;
    }

    private void run(List<BackupStore.HostOption> targets, String runId, List<Event> events) {
        java.util.function.Consumer<String> progress = message -> {
            if (runId == null) return;
            events.add(new Event(System.currentTimeMillis(), message));
            var current = status;
            status = new Status(true, current.total(), current.completed(), current.saved(), current.failures(),
                runId, List.copyOf(events));
        };
        int completed = 0;
        int saved = 0;
        var failures = new ArrayList<Failure>();
        try {
            for (var target : targets) {
                try {
                    if (!target.ready()) throw new IOException("Configure e valide a autenticação do host.");
                    var result = capture.capture(target.id(), progress);
                    progress.accept("Salvando backup");
                    store.insert(target.id(), result);
                    progress.accept("Backup concluído com sucesso");
                    saved++;
                } catch (InterruptedException ex) {
                    progress.accept("Backup interrompido");
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception ex) {
                    LOGGER.warn("Backup failed for host {} ({})", target.id(), ex.getClass().getSimpleName());
                    String message = ex.getClass() == IOException.class
                        ? ex.getMessage() : "Falha ao coletar ou armazenar a configuração.";
                    failures.add(new Failure(target.name(), message));
                    progress.accept("Erro: " + message);
                }
                status = new Status(true, targets.size(), ++completed, saved, List.copyOf(failures), runId, List.copyOf(events));
            }
        } finally {
            status = new Status(false, targets.size(), completed, saved, List.copyOf(failures), runId, List.copyOf(events));
        }
    }

    @PreDestroy
    void close() {
        worker.shutdownNow();
    }
}
