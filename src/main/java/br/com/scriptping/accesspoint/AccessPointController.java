package br.com.scriptping.accesspoint;

import java.util.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.web.server.ResponseStatusException;

@Controller
public class AccessPointController {
    private final AccessPointService service;
    public AccessPointController(AccessPointService service) { this.service = service; }
    @GetMapping("/ap") public String index() { return "access-points"; }
    @GetMapping("/ap/{id}") public String page(@PathVariable String id, Model model) {
        model.addAttribute("host", service.pageHost(id)); return "host";
    }
    @GetMapping("/ap/{id}/latency/{assessment}")
    public String latencySummary(@PathVariable String id, @PathVariable String assessment, Model model) {
        model.addAttribute("host", service.pageHost(id));
        model.addAttribute("assessment", assessment);
        return "ap-latency-summary";
    }
    @GetMapping("/api/ap") @ResponseBody
    public ResponseEntity<?> list() { return fresh(service.list()); }
    @GetMapping("/api/ap/candidates") @ResponseBody
    public ResponseEntity<?> candidates() { return fresh(service.candidates()); }
    public record Selection(List<String> hostIds) {}
    @PostMapping("/api/ap") @ResponseBody
    public ResponseEntity<?> define(@RequestBody Selection selection) {
        service.define(selection.hostIds()); return fresh(service.list());
    }
    @GetMapping("/api/ap/{id}/{section:overview|interfaces|traffic|wireless}") @ResponseBody
    public ResponseEntity<?> live(@PathVariable String id, @PathVariable String section,
        @RequestParam(required = false) String selectedInterface) {
        var snapshot = service.read(id, section, selectedInterface);
        return fresh(snapshot);
    }
    @GetMapping("/api/ap/{id}/configuration") @ResponseBody
    public ResponseEntity<?> configuration(@PathVariable String id) { return fresh(service.configuration(id)); }
    @PutMapping("/api/ap/{id}/configuration") @ResponseBody
    public ResponseEntity<?> configure(@PathVariable String id, @RequestBody AccessPointService.ConfigurationInput input) {
        return fresh(service.save(id, input));
    }
    @PostMapping("/api/ap/{id}/configuration/test") @ResponseBody
    public ResponseEntity<?> test(@PathVariable String id, @RequestBody AccessPointService.ConfigurationInput input) {
        return fresh(service.test(id, input));
    }
    @DeleteMapping("/api/ap/{id}") @ResponseBody
    public ResponseEntity<?> delete(@PathVariable String id) { service.delete(id); return fresh(Map.of("deleted", true)); }
    private ResponseEntity<?> fresh(Object body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
    @ExceptionHandler(ResponseStatusException.class) @ResponseBody
    public ResponseEntity<?> error(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).cacheControl(CacheControl.noStore())
            .body(Map.of("message", Objects.requireNonNullElse(e.getReason(), "Consulta indisponível.")));
    }
}
