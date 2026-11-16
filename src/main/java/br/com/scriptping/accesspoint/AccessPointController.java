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
    private final br.com.scriptping.client.radio.RadioVisitService radio;
    public AccessPointController(AccessPointService service, br.com.scriptping.client.radio.RadioVisitService radio) {
        this.service = service; this.radio = radio;
    }
    @GetMapping("/ap") public String index() { return "access-points"; }
    @GetMapping("/ap/{id}") public String page(@PathVariable String id, Model model, jakarta.servlet.http.HttpSession owner) {
        model.addAttribute("radioVisit", radio.open(service.host(id).id(), owner.getId()));
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
        @RequestParam(required = false) String selectedInterface,
        @RequestHeader(value = "X-Radio-Visit", required = false) String visit,
        jakarta.servlet.http.HttpSession owner) {
        if (visit != null) {
            radio.owned(visit, id, owner.getId());
            if (!radio.active(visit)) return ResponseEntity.status(HttpStatus.GONE).body(Map.of("message", "Visita encerrada. Reabrindo a página."));
        }
        var snapshot = service.read(id, section, selectedInterface);
        if (visit != null && section.equals("wireless")) radio.observe(visit, id, owner.getId(), snapshot);
        return fresh(snapshot);
    }
    private ResponseEntity<?> fresh(Object body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
    @ExceptionHandler(ResponseStatusException.class) @ResponseBody
    public ResponseEntity<?> error(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).cacheControl(CacheControl.noStore())
            .body(Map.of("message", Objects.requireNonNullElse(e.getReason(), "Consulta indisponível.")));
    }
}
