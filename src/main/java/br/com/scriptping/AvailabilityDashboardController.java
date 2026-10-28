package br.com.scriptping;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class AvailabilityDashboardController {
    private final AvailabilityDashboard dashboard;
    public AvailabilityDashboardController(AvailabilityDashboard dashboard) { this.dashboard = dashboard; }
    public record Response(java.util.List<Host.Group> groups, AvailabilityDashboard.View view) {}
    @GetMapping("/api/availability")
    public ResponseEntity<Response> data(@RequestParam(defaultValue = "2880") int minutes,
        @RequestParam(defaultValue = "") String groupId) {
        if (!AvailabilityDashboard.MINUTES.contains(minutes))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Período inválido");
        var publication = dashboard.current();
        var periods = publication.views().get(groupId);
        if (periods == null && !groupId.isEmpty() && !publication.views().isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Grupo indisponível");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new Response(publication.groups(), periods == null ? null : periods.get(minutes)));
    }

    public record Page(int number, int pages, int total, int selectedTotal, int size) {}
    public record DashboardResponse(java.util.List<Host.Group> groups, AvailabilityDashboard.View view,
        Page page, String collectionStatus) {}

    @GetMapping("/api/availability/dashboard")
    public ResponseEntity<DashboardResponse> dashboard(
        @RequestParam(defaultValue = "2880") int minutes,
        @RequestParam(defaultValue = "") String groupId,
        @RequestParam(defaultValue = "") String q,
        @RequestParam(defaultValue = "name") String sort,
        @RequestParam(defaultValue = "asc") String direction,
        @RequestParam(defaultValue = "1") int page) {
        Response original = data(minutes, groupId).getBody();
        var view = original.view();
        if (view == null) return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new DashboardResponse(original.groups(), null, new Page(1, 1, 0, 0, 20), "CARREGANDO"));
        String query = normalize(q.strip());
        var rows = view.hosts().stream().filter(r -> normalize(r.name() + " " + r.ip() + " " + r.id()).contains(query))
            .sorted(comparator(sort, "desc".equals(direction))).toList();
        int pages = Math.max(1, (rows.size() + 19) / 20), number = Math.max(1, Math.min(page, pages));
        var subset = rows.subList((number - 1) * 20, Math.min(number * 20, rows.size()));
        String collection = view.hosts().isEmpty() ? "SEM_HOSTS"
            : view.hosts().stream().allMatch(r -> r.status().equals("SEM_DADOS") || r.status().equals("SEM_IP")) ? "SEM_DADOS"
            : view.hosts().stream().anyMatch(r -> !r.collectorHealthy() || !r.fresh()) ? "PARCIAL" : "RECEBENDO";
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new DashboardResponse(original.groups(),
            new AvailabilityDashboard.View(view.report(), subset), new Page(number, pages, rows.size(), view.hosts().size(), 20), collection));
    }
    private static String normalize(String value) {
        return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(java.util.Locale.ROOT);
    }
    private static java.util.Comparator<AvailabilityDashboard.Row> comparator(String sort, boolean descending) {
        java.util.function.Function<AvailabilityDashboard.Row, String> text = switch (sort) {
            case "groups" -> r -> r.groups().stream().map(Host.Group::name).sorted().collect(java.util.stream.Collectors.joining(", "));
            case "status" -> AvailabilityDashboard.Row::status;
            default -> AvailabilityDashboard.Row::name;
        };
        java.util.Comparator<AvailabilityDashboard.Row> order;
        if (java.util.Set.of("operational", "technical", "loss", "latency").contains(sort)) {
            java.util.Comparator<Double> values = descending ? java.util.Comparator.reverseOrder() : java.util.Comparator.naturalOrder();
            order = java.util.Comparator.comparing(r -> switch (sort) {
                case "operational" -> r.metrics().operational(); case "technical" -> r.metrics().technical();
                case "loss" -> r.metrics().loss(); default -> r.metrics().latency();
            }, java.util.Comparator.nullsLast(values));
        } else {
            order = java.util.Comparator.comparing(r -> normalize(text.apply(r)));
            if (descending) order = order.reversed();
        }
        return order.thenComparing(AvailabilityDashboard.Row::id);
    }
}
