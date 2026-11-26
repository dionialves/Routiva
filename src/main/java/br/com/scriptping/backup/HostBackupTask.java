package br.com.scriptping.backup;

import br.com.scriptping.scheduling.ScheduledTask;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

@Component
class HostBackupTask implements ScheduledTask {
    private final BackupJobs jobs;

    HostBackupTask(BackupJobs jobs) {
        this.jobs = jobs;
    }

    @Override public String key() { return "host-backup"; }

    @Override public String label() { return "Backup de hosts"; }

    @Override
    public Outcome execute(Map<String, String> parameters) throws InterruptedException {
        return backup(parameters, detail -> {});
    }

    @Override
    public Outcome execute(Map<String, String> parameters, Context context) throws InterruptedException {
        return backup(parameters, context.report());
    }

    private Outcome backup(Map<String, String> parameters, Consumer<Detail> report) throws InterruptedException {
        validate(parameters);
        var result = jobs.runScheduled(report);
        if (result.total() == 0) return Outcome.completed("Nenhum host habilitado para backup.");
        if (result.saved() == 0) throw new IllegalStateException("Nenhum backup foi salvo. Consulte os detalhes por host.");
        int failed = result.failures().size();
        return new Outcome(failed > 0, "%d hosts: %d backups salvos, %d com falha."
            .formatted(result.total(), result.saved(), failed));
    }
}
