package br.com.scriptping.incidents;

import br.com.scriptping.zabbix.*;
import java.util.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.*;
import org.springframework.beans.factory.annotation.Value;

@Controller
public class IncidentsController {
    private final ZabbixService inventory;
    private final ZabbixClient zabbix;
    public IncidentsController(ZabbixService inventory, ZabbixClient zabbix) {
        this.inventory = inventory;
        this.zabbix = zabbix;
    }

    public record Row(String eventId, String name, int severity, String severityLabel,
                      long timestamp, List<Host> hosts, boolean acknowledged, boolean hasMessages, int messageCount) {}
    private static final List<String> SEVERITIES = List.of("Não classificada", "Informação", "Atenção", "Média", "Alta", "Desastre");

    @Value("${spring.jackson.time-zone:America/Sao_Paulo}")
    private String timeZone = "America/Sao_Paulo";

    public record Page(List<Row> rows, int total, int page, int pages, int size, long updatedAt, String warning) {}

    @GetMapping("/incidentes")
    public String page(@RequestParam(defaultValue = "") String groupId,
                       @RequestParam(defaultValue = "") String hostId,
                       @RequestParam(defaultValue = "") String q,
                       @RequestParam(defaultValue = "") String severity,
                       Model model, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        var hosts = inventory.hosts();
        var groups = hosts.stream().flatMap(h -> h.groups().stream()).distinct()
            .sorted(Comparator.comparing(Host.Group::name, String.CASE_INSENSITIVE_ORDER)).toList();
        model.addAttribute("groups", groups);
        model.addAttribute("hosts", hosts);
        model.addAttribute("groupId", groupId);
        model.addAttribute("hostId", hostId);
        model.addAttribute("q", q);
        model.addAttribute("severity", severity);
        model.addAttribute("severities", SEVERITIES);
        model.addAttribute("timeZone", timeZone);
        return "incidents";
    }

    @GetMapping("/api/incidents")
    @ResponseBody
    public ResponseEntity<Page> incidents(@RequestParam(defaultValue = "") String groupId,
            @RequestParam(defaultValue = "") String hostId, @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "") String severity, @RequestParam(defaultValue = "date") String sort,
            @RequestParam(defaultValue = "desc") String direction, @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        return query(groupId, hostId, q, severity, sort, direction, page,
            Set.of(10, 25, 50, 100).contains(size) ? size : 50);
    }

    // One filtered snapshot: the table paginates each calendar period locally.
    @GetMapping("/api/incidents/grouped-data")
    @ResponseBody
    public ResponseEntity<Page> groupedData(@RequestParam(defaultValue = "") String groupId,
            @RequestParam(defaultValue = "") String hostId,
            @RequestParam(defaultValue = "") String severity) {
        return query(groupId, hostId, "", severity, "date", "desc", 1, Integer.MAX_VALUE);
    }

    public record QueryRequest(String groupId, String hostId, String severity) {}

    @PostMapping("/api/incidents/grouped-data")
    @ResponseBody
    public ResponseEntity<Page> groupedData(@RequestBody QueryRequest filters) {
        return groupedData(Objects.requireNonNullElse(filters.groupId(), ""),
            Objects.requireNonNullElse(filters.hostId(), ""), Objects.requireNonNullElse(filters.severity(), ""));
    }

    public record UpdateRequest(String message, boolean acknowledge, boolean close, Integer severity) {}

    @GetMapping("/api/incidents/{eventId}")
    @ResponseBody
    public ZabbixClient.IncidentDetails details(@PathVariable String eventId) {
        if (!eventId.matches("[0-9]+")) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST);
        try {
            var details = zabbix.incidentDetails(eventId, inventory.hosts().stream().map(Host::id).toList());
            if (details == null) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND, "Incidente indisponível.");
            return details;
        } catch (IllegalStateException ex) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_GATEWAY, "Não foi possível consultar o incidente no Zabbix.");
        }
    }

    @PostMapping("/api/incidents/{eventId}/updates")
    @ResponseBody
    public Map<String, String> update(@PathVariable String eventId, @RequestBody UpdateRequest body,
                                     org.springframework.security.core.Authentication authentication) {
        if (authentication == null || authentication.getAuthorities().stream().noneMatch(a -> a.getAuthority().equals("ROLE_ADMIN")))
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.FORBIDDEN);
        String message = body.message() == null ? "" : body.message().strip();
        if (message.length() > 1800 || body.severity() != null && (body.severity() < 0 || body.severity() > 5))
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "Mensagem ou severidade inválida.");
        var current = details(eventId);
        if (current.closed() || current.closing())
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.CONFLICT, "Incidente encerrado ou com fechamento solicitado.");
        if ((body.close() || body.severity() != null) && !current.writable() || body.close() && !current.manualClose())
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.FORBIDDEN, "Operação não permitida para este incidente.");
        int action = (body.close() ? 1 : 0) | (body.acknowledge() && !current.acknowledged() ? 2 : 0)
            | (body.severity() != null && body.severity() != current.severity() ? 8 : 0);
        if (action == 0 && message.isEmpty())
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe uma mensagem ou selecione uma alteração.");
        // Preserve the local actor in the Zabbix history even when the integration account is shared.
        String actor = authentication.getPrincipal() instanceof br.com.scriptping.security.UserPrincipal user
            ? user.username() : authentication.getName();
        if (actor.length() > 200) actor = actor.substring(0, 200);
        try {
            zabbix.updateIncident(eventId, action | 4, actor + ": " + (message.isEmpty() ? "Atualização do incidente." : message), (action & 8) != 0 ? body.severity() : null);
        } catch (Exception ex) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "Não foi possível confirmar a atualização. Consulte o histórico antes de tentar novamente; verifique também as permissões no Zabbix.");
        }
        return Map.of("message", body.close() ? "Fechamento solicitado ao Zabbix." : "Incidente atualizado.");
    }

    private ResponseEntity<Page> query(String groupId, String hostId, String q, String severity,
            String sort, String direction, int page, int size) {
        var selectedGroups = Set.copyOf(Arrays.asList(groupId.split(",")));
        var selectedHosts = Set.copyOf(Arrays.asList(hostId.split(",")));
        var selected = inventory.hosts().stream()
            .filter(h -> groupId.isEmpty() || h.groups().stream().anyMatch(g -> selectedGroups.contains(g.id())))
            .filter(h -> hostId.isEmpty() || selectedHosts.contains(h.id())).toList();
        var rows = new ArrayList<Row>();
        String warning = "";
        try {
            var events = zabbix.activeIncidents(selected.stream().map(Host::id).toList());
            var eventHosts = new LinkedHashMap<String, List<Host>>();
            var unique = new LinkedHashMap<String, ZabbixClient.Incident>();
            for (var host : selected) {
                if (!events.containsKey(host.id())) warning = "Dados parciais: alguns hosts não estão disponíveis no Zabbix.";
                for (var incident : events.getOrDefault(host.id(), List.of())) {
                    if (incident.eventId().isBlank()) {
                        warning = "Dados parciais: eventos sem identificador não puderam ser apresentados.";
                        continue;
                    }
                    unique.put(incident.eventId(), incident);
                    var related = eventHosts.computeIfAbsent(incident.eventId(), ignored -> new ArrayList<>());
                    if (!related.contains(host)) related.add(host);
                }
            }
            String query = q.strip().toLowerCase(Locale.ROOT);
            var selectedSeverities = Set.copyOf(Arrays.asList(severity.split(",")));
            unique.forEach((key, event) -> {
                if (!severity.isEmpty() && !selectedSeverities.contains(Integer.toString(event.severity()))) return;
                if (!query.isEmpty() && !event.name().toLowerCase(Locale.ROOT).contains(query)
                    && eventHosts.get(key).stream().noneMatch(h -> h.name().toLowerCase(Locale.ROOT).contains(query)
                        || h.ip().toLowerCase(Locale.ROOT).contains(query))) return;
                int level = event.severity();
                rows.add(new Row(event.eventId(), event.name(), level,
                    level >= 0 && level < SEVERITIES.size() ? SEVERITIES.get(level) : "Desconhecida",
                    event.openedAt(), List.copyOf(eventHosts.get(key)), event.acknowledged(), event.hasMessages(), event.messageCount()));
            });
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).cacheControl(CacheControl.noStore())
                .body(new Page(List.of(), 0, 1, 1, size, 0, "Não foi possível atualizar os incidentes do Zabbix"));
        }
        Comparator<Row> order = Comparator.comparingLong(Row::timestamp);
        // Longer durations correspond to earlier opening timestamps.
        boolean ascendingTime = "asc".equals(direction) != "duration".equals(sort);
        if (!ascendingTime) order = order.reversed();
        rows.sort(order.thenComparing(Row::eventId));
        int total = rows.size();
        int pages = Math.max(1, (int) ((total + (long) size - 1) / size));
        page = Math.clamp(page, 1, pages);
        int from = Math.min(total, (page - 1) * size);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Page(
            List.copyOf(rows.subList(from, Math.min(total, from + size))), total, page, pages, size,
            System.currentTimeMillis(), warning));
    }
}
