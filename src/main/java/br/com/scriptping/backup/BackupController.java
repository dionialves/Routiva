package br.com.scriptping.backup;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Controller
class BackupController {
    private final BackupStore store;
    private final BackupJobs jobs;

    BackupController(BackupStore store, BackupJobs jobs) {
        this.store = store;
        this.jobs = jobs;
    }

    @GetMapping("/backup")
    String page() { return "backup"; }

    @GetMapping("/api/backups")
    @ResponseBody List<BackupStore.Entry> list() { return store.list(); }

    @GetMapping("/api/backups/hosts")
    @ResponseBody List<BackupStore.HostOption> hosts() { return store.inventory(); }

    @GetMapping("/api/host-configurations/{id}/backup")
    @ResponseBody BackupStore.Settings settings(@PathVariable String id) { return store.settings(id); }

    @PutMapping("/api/host-configurations/{id}/backup")
    @ResponseBody BackupStore.Settings save(@PathVariable String id, @RequestBody BackupStore.Settings settings) {
        return store.save(id, settings);
    }

    record RunRequest(String hostId) {}

    @PostMapping("/api/backups/run")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @ResponseBody BackupJobs.Status run(@RequestBody(required = false) RunRequest input) {
        if (input == null) return jobs.start(null);
        if (input.hostId() == null || input.hostId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Selecione um host para o backup.");
        }
        return jobs.start(input.hostId());
    }

    @GetMapping("/api/backups/run")
    @ResponseBody BackupJobs.Status status() { return jobs.status(); }

    @GetMapping(value = "/api/backups/{id}/text", produces = "text/plain;charset=UTF-8")
    @ResponseBody String text(@PathVariable String id) {
        var file = store.file(id);
        if (!file.entry().text()) throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Baixe este arquivo.");
        return new String(file.content(), StandardCharsets.UTF_8);
    }

    @GetMapping("/api/backups/{id}/download")
    ResponseEntity<byte[]> download(@PathVariable String id) {
        var file = store.file(id);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.entry().filename()).build().toString())
            .body(file.content());
    }

    @DeleteMapping("/api/backups/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable String id) { store.delete(id); }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, String>> invalid(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of("message", ex.getReason()));
    }
}
