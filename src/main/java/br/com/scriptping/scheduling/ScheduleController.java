package br.com.scriptping.scheduling;

import java.util.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;

@Controller
@RequestMapping("/configuracao/agendador")
public class ScheduleController {
    private final ScheduleStore store;
    private final TaskCatalog catalog;
    private final ScheduleEngine engine;
    public ScheduleController(ScheduleStore store, TaskCatalog catalog, ScheduleEngine engine) {
        this.store = store; this.catalog = catalog; this.engine = engine;
    }
    @GetMapping public String page(Model model) { model.addAttribute("tasks", catalog.choices()); return "task-scheduler"; }
    @GetMapping("/api") @ResponseBody public Map<String, Object> data() {
        return Map.of("schedules", store.all(), "latest", store.latest(), "tasks", catalog.choices());
    }
    @GetMapping("/api/{id}/history") @ResponseBody
    public Map<String, Object> history(@PathVariable String id, @RequestParam(defaultValue = "0") int page) {
        long count = store.historyCount(id);
        int current = (int) Math.min(Math.max(0, page), Math.max(0, (count - 1) / 25));
        var history = store.history(id, current);
        return Map.of("history", history, "details", store.details(history), "total", count, "page", current);
    }
    public record Request(String id, String taskKey, ScheduleRule rule, Map<String, String> parameters, boolean enabled) {}
    @PostMapping("/preview") @ResponseBody public Map<String, Object> preview(@RequestBody ScheduleRule rule) {
        return Map.of("description", rule.description(), "times", rule.preview(System.currentTimeMillis()));
    }
    @PostMapping("/api") @ResponseBody public Map<String, String> save(@RequestBody Request body) {
        if (body.rule() == null) throw new IllegalArgumentException("Informe a programação.");
        var task = catalog.require(body.taskKey());
        var parameters = body.parameters() == null ? Map.<String, String>of() : Map.copyOf(body.parameters());
        task.validate(parameters);
        boolean create = body.id() == null || body.id().isBlank();
        String id = create ? UUID.randomUUID().toString() : body.id();
        long now = System.currentTimeMillis();
        store.save(new ScheduleStore.Entry(id, task.label().substring(0, Math.min(120, task.label().length())), task.key(), task.label(), body.rule(), parameters,
            body.enabled(), body.rule().next(now)), create, now);
        return Map.of("message", "Agendamento salvo.", "id", id);
    }
    @PostMapping("/api/{id}/run") @ResponseBody
    public ResponseEntity<?> run(@PathVariable String id) {
        return ResponseEntity.accepted().body(Map.of("message", "Execução iniciada. Acompanhe o histórico.", "executionId", engine.runNow(id)));
    }
    @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class) @ResponseBody
    public ResponseEntity<?> duplicate() {
        return ResponseEntity.status(org.springframework.http.HttpStatus.CONFLICT).body(Map.of("message", "Já existe um agendamento para esta atividade. Abra a tarefa na tabela para editar ou executar."));
    }
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class) @ResponseBody
    public ResponseEntity<?> status(org.springframework.web.server.ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("message", java.util.Objects.requireNonNullElse(error.getReason(), "Operação indisponível.")));
    }
    @PostMapping("/api/{id}/{action}") @ResponseBody public Map<String, String> change(@PathVariable String id, @PathVariable String action) {
        store.change(id, action, System.currentTimeMillis());
        return Map.of("message", action.equals("delete") ? "Agendamento excluído. Histórico preservado." : "Agendamento atualizado.");
    }
    @ExceptionHandler(IllegalArgumentException.class) @ResponseBody
    public ResponseEntity<?> invalid(IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("message", e.getMessage())); }
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class) @ResponseBody
    public ResponseEntity<?> unreadable() { return ResponseEntity.badRequest().body(Map.of("message", "Programação inválida. Confira os campos informados.")); }
}
