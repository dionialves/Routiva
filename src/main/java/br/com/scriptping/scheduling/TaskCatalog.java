package br.com.scriptping.scheduling;

import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class TaskCatalog {
    private final Map<String, ScheduledTask> tasks;
    public TaskCatalog(List<ScheduledTask> tasks) {
        var catalog = new LinkedHashMap<String, ScheduledTask>();
        for (var task : tasks) {
            if (!task.key().matches("[a-z][a-z0-9-]{0,79}") || catalog.put(task.key(), task) != null)
                throw new IllegalStateException("Identificador de tarefa inválido ou duplicado.");
        }
        this.tasks = Collections.unmodifiableMap(catalog);
    }
    public record Choice(String key, String label) {}
    public List<Choice> choices() { return tasks.values().stream().map(t -> new Choice(t.key(), t.label())).toList(); }
    public ScheduledTask require(String key) {
        var task = tasks.get(key);
        if (task == null) throw new IllegalArgumentException("Tarefa indisponível. Selecione uma tarefa do catálogo.");
        return task;
    }
}
