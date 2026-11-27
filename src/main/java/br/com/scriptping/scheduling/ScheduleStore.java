package br.com.scriptping.scheduling;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Repository
public class ScheduleStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    public ScheduleStore(JdbcTemplate jdbc, ObjectMapper json, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.json = json; this.tx = new TransactionTemplate(manager);
    }
    public record Entry(String id, String name, String taskKey, String taskLabel, ScheduleRule rule,
                        Map<String, String> parameters, boolean enabled, long nextRun) {}
    public record Execution(String id, String scheduleId, String scheduleName, String taskLabel, String zone,
                            long scheduledAt, Long startedAt, Long finishedAt, String state, String message) {}
    private Entry row(java.sql.ResultSet r, int n) throws java.sql.SQLException {
        try {
            return new Entry(r.getString("id"), r.getString("name"), r.getString("task_key"), r.getString("task_label"),
                json.readValue(r.getString("rule_json"), ScheduleRule.class),
                json.readValue(r.getString("parameters_json"), new TypeReference<Map<String, String>>() {}),
                r.getBoolean("enabled"), r.getLong("next_run"));
        } catch (java.io.IOException e) { throw new IllegalStateException("Agendamento armazenado inválido.", e); }
    }
    public List<Entry> all() { return jdbc.query("SELECT * FROM task_schedule WHERE deleted = FALSE ORDER BY created_at DESC, id", this::row); }
    public List<Execution> history(int page) {
        return jdbc.query("SELECT * FROM task_execution ORDER BY scheduled_at DESC, id LIMIT 25 OFFSET ?", (r, n) ->
            new Execution(r.getString("id"), r.getString("schedule_id"), r.getString("schedule_name"), r.getString("task_label"),
                r.getString("zone"), r.getLong("scheduled_at"), (Long) r.getObject("started_at"),
                (Long) r.getObject("finished_at"), r.getString("state"), r.getString("message")), page * 25);
    }
    public long historyCount() { return jdbc.queryForObject("SELECT COUNT(*) FROM task_execution", Long.class); }
    public List<Execution> history(String scheduleId, int page) {
        return historySummary(scheduleId, page, 25);
    }
    public List<Execution> historySummary(String scheduleId, int page, int size) {
        return historySummary(scheduleId, page, size, "scheduled_at", true);
    }

    public List<Execution> historySummary(String scheduleId, int page, int size, String sort, boolean descending) {
        String column = switch (sort) {
            case "started_at" -> "started_at";
            case "finished_at" -> "finished_at";
            case "state" -> "state";
            default -> "scheduled_at";
        };
        String direction = descending ? " DESC" : " ASC";
        return jdbc.query("SELECT * FROM task_execution WHERE schedule_id=? ORDER BY "
            + column + direction + " NULLS LAST, id LIMIT ? OFFSET ?", this::execution, scheduleId, size, (long) page * size);
    }

    public Optional<Execution> execution(String scheduleId, String executionId) {
        return jdbc.query("SELECT * FROM task_execution WHERE schedule_id=? AND id=?", this::execution,
            scheduleId, executionId).stream().findFirst();
    }

    private Execution execution(java.sql.ResultSet r, int row) throws java.sql.SQLException {
        return new Execution(r.getString("id"), r.getString("schedule_id"), r.getString("schedule_name"), r.getString("task_label"),
            r.getString("zone"), r.getLong("scheduled_at"), (Long) r.getObject("started_at"),
            (Long) r.getObject("finished_at"), r.getString("state"), r.getString("message"));
    }

    public long historyCount(String scheduleId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM task_execution WHERE schedule_id=?", Long.class, scheduleId);
    }
    public Map<String, Execution> latest() {
        var results = jdbc.query("SELECT e.* FROM task_execution e JOIN task_schedule s ON s.id=e.schedule_id AND s.deleted=FALSE WHERE NOT EXISTS (SELECT 1 FROM task_execution newer WHERE newer.schedule_id=e.schedule_id AND (newer.scheduled_at>e.scheduled_at OR (newer.scheduled_at=e.scheduled_at AND newer.id>e.id)))", (r, n) ->
            new Execution(r.getString("id"), r.getString("schedule_id"), r.getString("schedule_name"), r.getString("task_label"),
                r.getString("zone"), r.getLong("scheduled_at"), (Long) r.getObject("started_at"),
                (Long) r.getObject("finished_at"), r.getString("state"), r.getString("message")));
        var result = new HashMap<String, Execution>();
        results.forEach(e -> result.put(e.scheduleId(), e)); return result;
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalArgumentException("Configuração inválida."); }
    }
    public void save(Entry entry, boolean create, long now) {
        boolean guidedCron = entry.rule().cron() != null;
        if (create) {
            // A manual run before the first save needs a history owner but has no planned occurrence.
            long nextRun = guidedCron && !entry.enabled() ? 0 : entry.nextRun();
            jdbc.update("INSERT INTO task_schedule(id,name,task_key,task_label,rule_json,parameters_json,enabled,next_run,created_at,updated_at,task_slot) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                entry.id(), entry.name(), entry.taskKey(), entry.taskLabel(), encode(entry.rule()), encode(entry.parameters()), entry.enabled(), nextRun, now, now, entry.taskKey());
            return;
        }
        tx.executeWithoutResult(status -> {
            var rows = jdbc.query("SELECT * FROM task_schedule WHERE id=? AND deleted=FALSE FOR UPDATE", this::row, entry.id());
            if (rows.isEmpty()) missing();
            var previous = rows.getFirst();
            if (!previous.taskKey().equals(entry.taskKey()) && isRunning(previous.taskKey()))
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
                    "Aguarde o fim da execução para alterar a tarefa.");
            boolean enabled = guidedCron ? previous.enabled() || previous.nextRun() == 0 : entry.enabled();
            jdbc.update("UPDATE task_schedule SET name=?,task_key=?,task_label=?,rule_json=?,parameters_json=?,enabled=?,next_run=?,updated_at=?,task_slot=? WHERE id=?",
                entry.name(), entry.taskKey(), entry.taskLabel(), encode(entry.rule()), encode(entry.parameters()),
                enabled, entry.nextRun(), now, entry.taskKey(), entry.id());
        });
    }

    private boolean isRunning(String taskKey) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM task_execution e JOIN task_schedule s ON s.id=e.schedule_id WHERE s.task_key=? AND e.state='RUNNING'",
            Integer.class, taskKey) > 0;
    }

    public void change(String id, String action, long now) {
        tx.executeWithoutResult(s -> {
            var rows = jdbc.query("SELECT * FROM task_schedule WHERE id=? AND deleted=FALSE FOR UPDATE", this::row, id);
            if (rows.isEmpty()) missing();
            var entry = rows.getFirst();
            switch (action) {
                case "pause" -> jdbc.update("UPDATE task_schedule SET enabled=FALSE,updated_at=? WHERE id=?", now, id);
                case "enable" -> jdbc.update("UPDATE task_schedule SET enabled=TRUE,next_run=?,updated_at=? WHERE id=?", entry.rule().next(now), now, id);
                case "delete" -> jdbc.update("UPDATE task_schedule SET deleted=TRUE,enabled=FALSE,task_slot=NULL,updated_at=? WHERE id=?", now, id);
                default -> throw new IllegalArgumentException("Ação inválida.");
            }
        });
    }
    private void missing() { throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Agendamento não encontrado."); }
    public record Claim(Entry entry, String executionId) {}
    public List<Claim> claim(long now, int capacity) {
        return tx.execute(s -> {
            var claimed = new ArrayList<Claim>();
            var due = jdbc.query("SELECT * FROM task_schedule WHERE enabled=TRUE AND deleted=FALSE AND next_run<=? ORDER BY id FOR UPDATE", this::row, now);
            for (var entry : due) {
                String state = "RUNNING", message = "Execução iniciada.";
                if (now - entry.nextRun() > 60000) { state = "MISSED"; message = "Horário perdido; ocorrências atrasadas não serão executadas."; }
                else if (isRunning(entry.taskKey())) {
                    state = "SKIPPED"; message = "Ignorada: outra execução desta tarefa ainda está em andamento.";
                } else if (claimed.size() >= capacity) { state = "SKIPPED"; message = "Ignorada: limite de execuções simultâneas atingido."; }
                String id = UUID.randomUUID().toString();
                boolean running = state.equals("RUNNING");
                jdbc.update("INSERT INTO task_execution(id,schedule_id,schedule_name,task_label,zone,scheduled_at,started_at,finished_at,state,message) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    id, entry.id(), entry.name(), entry.taskLabel(), entry.rule().zone(), entry.nextRun(), running ? now : null, running ? null : now, state, message);
                jdbc.update("UPDATE task_schedule SET next_run=? WHERE id=?", entry.rule().next(now), entry.id());
                if (running) claimed.add(new Claim(entry, id));
            }
            return claimed;
        });
    }
    public Claim claimNow(String id, long now, int capacity) {
        return tx.execute(status -> {
            var rows = jdbc.query("SELECT * FROM task_schedule WHERE id=? AND deleted=FALSE FOR UPDATE", this::row, id);
            if (rows.isEmpty()) missing();
            var entry = rows.getFirst();
            if (isRunning(entry.taskKey()))
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "Esta tarefa já está em execução.");
            if (capacity < 1) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "O executor está ocupado. Tente novamente após a conclusão das tarefas em andamento.");
            String execution = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO task_execution(id,schedule_id,schedule_name,task_label,zone,scheduled_at,started_at,state,message) VALUES(?,?,?,?,?,?,?,'RUNNING',?)",
                execution, entry.id(), entry.taskLabel(), entry.taskLabel(), entry.rule().zone(), now, now, "Execução manual iniciada.");
            return new Claim(entry, execution);
        });
    }
    public void detail(String executionId, ScheduledTask.Detail detail, long now) {
        int updated = jdbc.update("UPDATE task_execution_item SET host_name=?,state=?,message=?,updated_at=? WHERE execution_id=? AND host_id=?",
            detail.hostName(), detail.state(), detail.message(), now, executionId, detail.hostId());
        if (updated == 0) jdbc.update("INSERT INTO task_execution_item(execution_id,host_id,host_name,state,message,updated_at) VALUES(?,?,?,?,?,?)",
            executionId, detail.hostId(), detail.hostName(), detail.state(), detail.message(), now);
    }
    public Map<String, List<ScheduledTask.Detail>> details(List<Execution> executions) {
        var result = new LinkedHashMap<String, List<ScheduledTask.Detail>>();
        for (var execution : executions) result.put(execution.id(), jdbc.query(
            "SELECT * FROM task_execution_item WHERE execution_id=? ORDER BY updated_at,host_id", (r, n) ->
                new ScheduledTask.Detail(r.getString("host_id"), r.getString("host_name"), r.getString("state"), r.getString("message")), execution.id()));
        return result;
    }
    public void finish(String id, String state, String message, long now) {
        if (state.equals("INTERRUPTED") || state.equals("FAILED"))
            jdbc.update("UPDATE task_execution_item SET state=?,message=?,updated_at=? WHERE execution_id=? AND state IN ('RUNNING','PENDING')",
                state, state.equals("INTERRUPTED") ? "Processamento interrompido." : "Processamento encerrado com falha.", now, id);
        jdbc.update("UPDATE task_execution SET state=?,message=?,finished_at=? WHERE id=? AND state='RUNNING'", state, message, now, id);
    }
    public void recover(long now) {
        jdbc.update("UPDATE task_execution_item SET state='INTERRUPTED',message='Processamento interrompido pelo encerramento da aplicação.',updated_at=? WHERE state IN ('RUNNING','PENDING')", now);
        jdbc.update("UPDATE task_execution SET state='INTERRUPTED',message='Execução interrompida pelo encerramento da aplicação.',finished_at=? WHERE state='RUNNING'", now);
    }
}
