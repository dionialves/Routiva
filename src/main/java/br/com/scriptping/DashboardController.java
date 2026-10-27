package br.com.scriptping;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class DashboardController {

    private final MonitorService monitor;
    private final PacketLossAnalysisService analysis;
    private final PacketLossPolicy lossPolicy;

    public DashboardController(
        MonitorService monitor,
        PacketLossAnalysisService analysis,
        PacketLossPolicy lossPolicy
    ) {
        this.monitor = monitor;
        this.analysis = analysis;
        this.lossPolicy = lossPolicy;
    }

    // Keep the existing table field and sort keys for bookmarked URLs: hour=8 h, minute=1 h, current=5 min.
    public record Card(
        String id,
        List<String> groupIds,
        String name,
        String ip,
        String groupNames,
        int members,
        String detail,
        Double hour,
        Double minute,
        Double current,
        String color,
        String state,
        Long updatedAt,
        long lossAttempts,
        long lostPackets,
        long minuteLossAttempts,
        long minuteLostPackets,
        Double lossPercent,
        Double minuteLossPercent,
        boolean lossIssue,
        String diagnostic,
        long fiveMinuteLossAttempts,
        long fiveMinuteLostPackets,
        Double fiveMinuteLossPercent
    ) {
        public String updated() {
            return time(updatedAt);
        }

        public String lossPercentage() {
            return lossPercent == null ? "—" : format(lossPercent) + "%";
        }

        public String minuteLossPercentage() {
            return minuteLossPercent == null ? "—" : format(minuteLossPercent) + "%";
        }

        public String lossTitle() {
            return lossTitle(lostPackets, lossAttempts, lossPercent);
        }

        public String fiveMinuteLossPercentage() {
            return fiveMinuteLossPercent == null ? "—" : format(fiveMinuteLossPercent) + "%";
        }

        public String fiveMinuteLossTitle() {
            return lossTitle(fiveMinuteLostPackets, fiveMinuteLossAttempts, fiveMinuteLossPercent);
        }

        public String minuteLossTitle() {
            return lossTitle(minuteLostPackets, minuteLossAttempts, minuteLossPercent);
        }

        private static String lossTitle(long lost, long attempts, Double percent) {
            if (attempts == 0) return "Sem pings nesta janela";
            return lost + " de " + attempts + " pings";
        }

        public String format(Double value) {
            return value == null
                ? "—"
                : String.format(Locale.forLanguageTag("pt-BR"), "%.2f", value);
        }
    }

    public static String color(Double hour, Double minute, Double current, boolean offline) {
        if (offline) return "red";
        var values = Arrays.asList(hour, minute, current);
        if (values.stream().anyMatch(v -> v != null && v > 20)) return "red";
        if (values.stream().anyMatch(v -> v != null && v >= 15)) return "yellow";
        if (values.stream().anyMatch(Objects::isNull)) return "neutral";
        return "green";
    }

    @GetMapping({ "/", "/hosts", "/grupos" })
    public String page(
        jakarta.servlet.http.HttpServletRequest request,
        jakarta.servlet.http.HttpServletResponse response,
        @org.springframework.web.bind.annotation.RequestParam(defaultValue = "") String q,
        @org.springframework.web.bind.annotation.RequestParam(
            defaultValue = "all"
        ) String pingStatus,
        @org.springframework.web.bind.annotation.RequestParam(defaultValue = "") String groupId,
        @org.springframework.web.bind.annotation.RequestParam(defaultValue = "name") String sort,
        @org.springframework.web.bind.annotation.RequestParam(
            defaultValue = "asc"
        ) String direction,
        @org.springframework.web.bind.annotation.RequestParam(defaultValue = "1") int page,
        @org.springframework.web.bind.annotation.RequestHeader(
            value = "HX-Request",
            defaultValue = "false"
        ) boolean partial,
        Model model
    ) {
        response.setHeader("Vary", "HX-Request");
        boolean groupPage = request.getRequestURI().equals("/grupos");
        prepare(model, groupPage, q, pingStatus, groupId, sort, direction, page);
        model.addAttribute("partialResponse", partial);
        return partial ? "dashboard :: content" : "index";
    }

    // Preserve the former polling URL for clients that already have the page open.
    @GetMapping("/dashboard")
    public String fragment(Model model) {
        prepare(model, false, "", "all", "", "name", "asc", 1);
        model.addAttribute("partialResponse", true);
        return "dashboard :: content";
    }

    @SuppressWarnings("unchecked")
    private void prepare(
        Model model,
        boolean groupPage,
        String query,
        String requestedStatus,
        String requestedGroupId,
        String requestedSort,
        String requestedDirection,
        int requestedPage
    ) {
        populate(model);
        String selected = Set.of(
            "all",
            "green",
            "yellow",
            "red",
            "neutral",
            "gray",
            "normal",
            "high-latency",
            "packet-loss",
            "latency-and-loss"
        ).contains(requestedStatus)
            ? requestedStatus
            : "all";
        String groupId = groupPage ? "" : requestedGroupId.strip();
        var groupOptions = (List<Card>) model.getAttribute("groups");
        var selectedGroup = groupOptions
            .stream()
            .filter(card -> card.id().equals(groupId))
            .findFirst();
        var all = ((List<Card>) model.getAttribute(groupPage ? "groups" : "hosts"))
            .stream()
            .filter(card -> groupId.isEmpty() || card.groupIds().contains(groupId))
            .toList();
        model.addAttribute("groupId", groupId);
        model.addAttribute("groupOptions", groupOptions);
        model.addAttribute("groupUnavailable", !groupId.isEmpty() && selectedGroup.isEmpty());
        model.addAttribute(
            "selectedGroupName",
            groupId.isEmpty() ? "" : selectedGroup.map(Card::name).orElse("Grupo indisponível")
        );
        String search = normalize(query.strip());
        Set<String> columns = groupPage
            ? Set.of(
                  "name",
                  "members",
                  "hour",
                  "minute",
                  "current",
                  "lossHour",
                  "lossMinute",
                  "lossFiveMinutes",
                  "status",
                  "updated"
              )
            : Set.of(
                  "name",
                  "ip",
                  "groups",
                  "hour",
                  "minute",
                  "current",
                  "lossHour",
                  "lossMinute",
                  "lossFiveMinutes",
                  "status",
                  "updated"
              );
        String sort = columns.contains(requestedSort) ? requestedSort : "name";
        String direction = requestedDirection.equals("desc") ? "desc" : "asc";
        model.addAttribute("sort", sort);
        model.addAttribute("direction", direction);
        var filtered = all
            .stream()
            .filter(card -> normalize(card.name()).contains(search))
            .filter(card -> matchesStatus(card, selected))
            .sorted(sortComparator(sort, direction.equals("desc")))
            .toList();
        int pageSize = 20;
        int totalPages = Math.max(1, (filtered.size() + pageSize - 1) / pageSize);
        int currentPage = Math.max(1, Math.min(requestedPage, totalPages));
        int offset = (currentPage - 1) * pageSize;
        model.addAttribute(
            "cards",
            filtered.subList(offset, Math.min(offset + pageSize, filtered.size()))
        );
        model.addAttribute("currentPage", currentPage);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("filteredTotal", filtered.size());
        model.addAttribute("firstRecord", filtered.isEmpty() ? 0 : offset + 1);
        model.addAttribute("lastRecord", Math.min(offset + pageSize, filtered.size()));
        model.addAttribute("total", all.size());
        model.addAttribute("groupPage", groupPage);
        model.addAttribute("pageTitle", groupPage ? "Grupos de hosts" : "Hosts");
        model.addAttribute("pagePath", groupPage ? "/grupos" : "/hosts");
        model.addAttribute("q", query);
        model.addAttribute("pingStatus", selected);
    }

    private static String normalize(String value) {
        return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "")
            .toLowerCase(Locale.ROOT);
    }

    private static <T extends Comparable<? super T>> Comparator<Card> by(
        java.util.function.Function<Card, T> field,
        boolean descending
    ) {
        Comparator<T> values = Comparator.naturalOrder();
        if (descending) values = values.reversed();
        return Comparator.comparing(field, Comparator.nullsLast(values));
    }

    private static Comparator<Card> sortComparator(String column, boolean descending) {
        Comparator<Card> comparator = switch (column) {
            case "ip" -> by(c -> ipSortKey(c.ip()), descending);
            case "groups" -> by(c -> normalize(c.groupNames()), descending);
            case "members" -> by(Card::members, descending);
            case "hour" -> by(Card::hour, descending);
            case "minute" -> by(Card::minute, descending);
            case "current" -> by(Card::current, descending);
            case "lossHour" -> by(Card::lossPercent, descending);
            case "lossMinute" -> by(Card::minuteLossPercent, descending);
            case "lossFiveMinutes" -> by(Card::fiveMinuteLossPercent, descending);
            case "status" -> by(c -> normalize(c.state()), descending);
            case "updated" -> by(Card::updatedAt, descending);
            default -> by(c -> normalize(c.name()), descending);
        };
        return comparator.thenComparing(c -> normalize(c.name())).thenComparing(Card::id);
    }

    private static String ipSortKey(String ip) {
        if (!IpAddress.isValid(ip)) return null;
        try {
            byte[] bytes = java.net.InetAddress.getByName(ip).getAddress();
            return (
                String.format(Locale.ROOT, "%02d:", bytes.length) + HexFormat.of().formatHex(bytes)
            );
        } catch (Exception ex) {
            return null;
        }
    }

    private static boolean highLatency(Double hour, Double minute, Double current) {
        return Arrays.asList(hour, minute, current)
            .stream()
            .anyMatch(v -> v != null && v >= 15);
    }

    private static boolean matchesStatus(Card card, String selected) {
        return switch (selected) {
            case "all" -> true;
            case "normal" -> card.state().startsWith("Normal");
            case "high-latency" -> highLatency(card.hour(), card.minute(), card.current());
            case "packet-loss" -> card.lossIssue();
            case "latency-and-loss" -> card.lossIssue() &&
                highLatency(card.hour(), card.minute(), card.current());
            default -> card.color().equals(selected);
        };
    }

    private record Presentation(String color, String state, boolean lossIssue) {}

    private Presentation presentation(
        List<PacketLossAnalysis> results,
        Double hour,
        Double minute,
        Double current
    ) {
        String color = color(hour, minute, current, false);
        boolean high = highLatency(hour, minute, current);
        boolean issue = false;
        String state = high ? "Latência alta" : "Normal";
        var windowQuality = results
            .stream()
            .map(r -> {
                var s = r.stats();
                return java.util.stream.Stream.of(
                    lossPolicy.windowQuality(s.lossesEightHours(), s.attemptsEightHours()),
                    lossPolicy.windowQuality(s.lossesLastHour(), s.attemptsLastHour()),
                    lossPolicy.windowQuality(s.lossesFiveMinutes(), s.attemptsFiveMinutes())
                )
                    .max(Comparator.naturalOrder())
                    .orElse(PacketLossAnalysis.Quality.DADOS_INSUFICIENTES);
            })
            .max(Comparator.naturalOrder())
            .orElse(PacketLossAnalysis.Quality.DADOS_INSUFICIENTES);
        if (windowQuality == PacketLossAnalysis.Quality.CRITICO) {
            color = "red";
            state = "Crítico: perda de pacotes";
            issue = true;
        } else if (windowQuality == PacketLossAnalysis.Quality.ATENCAO) {
            if (!color.equals("red")) color = "yellow";
            state = "Atenção: perda de pacotes";
            issue = true;
        } else if (windowQuality == PacketLossAnalysis.Quality.DADOS_INSUFICIENTES && !high) {
            color = "neutral";
            state = "Dados insuficientes";
        }
        if (high && issue) state += " · latência alta";
        return new Presentation(color, state, issue);
    }

    private void populate(Model model) {
        long now = System.currentTimeMillis();
        var hosts = monitor.hosts();
        var analyses = analysis.analyze(hosts, now);
        List<Card> cards = new ArrayList<>();
        Map<String, Host.Group> groups = new TreeMap<>();
        Map<String, List<PacketLossAnalysis>> groupStats = new HashMap<>();
        Map<String, Integer> groupTotals = new HashMap<>();
        for (Host host : hosts) {
            var quality = analyses.get(host.id());
            var s = quality.stats();
            boolean valid = IpAddress.isValid(host.ip());
            boolean stale = s.measuredAt() != null && !quality.fresh();
            Double hour = valid ? s.eightHours() : null,
                minute = valid ? s.latencyHour() : null,
                current = valid ? s.fiveMinutes() : null;
            var display = presentation(List.of(quality), hour, minute, current);
            String state = host.acknowledgedIcmpProblem()
                ? "Incidente ICMP reconhecido no Zabbix"
                : valid
                  ? display.state()
                  : "Sem IP válido";
            String diagnostic = host.acknowledgedIcmpProblem()
                ? "Excluído das estatísticas do grupo"
                : !valid
                  ? null
                  : quality.diagnostic() +
                    (stale ? " · Coleta atrasada" : s.error() != null ? " · " + s.error() : "");
            if (Objects.equals(diagnostic, state)) diagnostic = null;
            String detail =
                (valid ? host.ip() : "Sem IP válido") +
                " • " +
                String.join(", ", host.groups().stream().map(Host.Group::name).toList());
            cards.add(
                new Card(
                    host.id(),
                    host.groups().stream().map(Host.Group::id).toList(),
                    host.name(),
                    valid ? host.ip() : "—",
                    String.join(", ", host.groups().stream().map(Host.Group::name).toList()),
                    0,
                    detail,
                    hour,
                    minute,
                    current,
                    valid && !host.acknowledgedIcmpProblem() ? display.color() : "gray",
                    state,
                    valid ? s.measuredAt() : null,
                    valid ? s.attemptsEightHours() : 0,
                    valid ? s.lossesEightHours() : 0,
                    valid ? s.attemptsLatencyHour() : 0,
                    valid ? s.lossesLatencyHour() : 0,
                    valid
                        ? lossPolicy.visiblePercentage(s.lossesEightHours(), s.attemptsEightHours())
                        : null,
                    valid
                        ? lossPolicy.visiblePercentage(
                              s.lossesLatencyHour(),
                              s.attemptsLatencyHour()
                          )
                        : null,
                    valid && !host.acknowledgedIcmpProblem() && display.lossIssue(),
                    diagnostic,
                    valid ? s.attemptsFiveMinutes() : 0,
                    valid ? s.lossesFiveMinutes() : 0,
                    valid
                        ? lossPolicy.visiblePercentage(
                              s.lossesFiveMinutes(),
                              s.attemptsFiveMinutes()
                          )
                        : null
                )
            );
            for (var g : host.groups()) {
                groups.put(g.id(), g);
                groupTotals.merge(g.id(), 1, Integer::sum);
                var list = groupStats.computeIfAbsent(g.id(), k -> new ArrayList<>());
                if (valid && !host.acknowledgedIcmpProblem()) list.add(quality);
            }
        }
        cards.sort(Comparator.comparing(Card::name, String.CASE_INSENSITIVE_ORDER));
        List<Card> groupCards = new ArrayList<>();
        for (var entry : groups.entrySet()) {
            var qualities = groupStats.get(entry.getKey());
            var list = qualities.stream().map(PacketLossAnalysis::stats).toList();
            int total = groupTotals.get(entry.getKey()),
                excluded = total - list.size();
            Double hour = avg(list, 0),
                minute = avg(list, 1),
                current = avg(list, 2);
            long attempts = list.stream().mapToLong(SampleStore.Stats::attemptsEightHours).sum();
            long lost = list.stream().mapToLong(SampleStore.Stats::lossesEightHours).sum();
            long minuteAttempts = list
                .stream()
                .mapToLong(SampleStore.Stats::attemptsLatencyHour)
                .sum();
            long minuteLost = list.stream().mapToLong(SampleStore.Stats::lossesLatencyHour).sum();
            long fiveAttempts = list
                .stream()
                .mapToLong(SampleStore.Stats::attemptsFiveMinutes)
                .sum();
            long fiveLost = list.stream().mapToLong(SampleStore.Stats::lossesFiveMinutes).sum();
            var display = presentation(qualities, hour, minute, current);
            String groupColor = list.isEmpty() ? "gray" : display.color();
            String state = list.isEmpty()
                ? "Sem hosts elegíveis para estatísticas"
                : display.state();
            String diagnostic = list.isEmpty() ? null : "Pior membro · " + display.state();
            long recognized = hosts
                .stream()
                .filter(
                    h ->
                        h.acknowledgedIcmpProblem() &&
                        IpAddress.isValid(h.ip()) &&
                        h
                            .groups()
                            .stream()
                            .anyMatch(g -> g.id().equals(entry.getKey()))
                )
                .count();
            long invalid = excluded - recognized;
            String detail =
                total +
                " hosts" +
                (invalid > 0 ? " · " + invalid + " sem IP válido" : "") +
                (recognized > 0
                    ? " · " + recognized + " com incidente reconhecido (excluídos)"
                    : "");
            groupCards.add(
                new Card(
                    entry.getKey(),
                    List.of(),
                    entry.getValue().name(),
                    "",
                    "",
                    total,
                    detail,
                    hour,
                    minute,
                    current,
                    groupColor,
                    state,
                    now,
                    attempts,
                    lost,
                    minuteAttempts,
                    minuteLost,
                    lossPolicy.visiblePercentage(lost, attempts),
                    lossPolicy.visiblePercentage(minuteLost, minuteAttempts),
                    display.lossIssue(),
                    diagnostic,
                    fiveAttempts,
                    fiveLost,
                    lossPolicy.visiblePercentage(fiveLost, fiveAttempts)
                )
            );
        }
        groupCards.sort(Comparator.comparing(Card::name, String.CASE_INSENSITIVE_ORDER));
        model.addAttribute("hosts", cards);
        model.addAttribute("groups", groupCards);
        model.addAttribute("status", monitor.status());
        model.addAttribute("lastSync", time(monitor.lastSync()));
        model.addAttribute("updated", time(now));
    }

    private static Double avg(List<SampleStore.Stats> list, int metric) {
        return list
            .stream()
            .map(s ->
                metric == 0 ? s.eightHours() : metric == 1 ? s.latencyHour() : s.fiveMinutes()
            )
            .filter(Objects::nonNull)
            .mapToDouble(Double::doubleValue)
            .average()
            .stream()
            .boxed()
            .findFirst()
            .orElse(null);
    }

    private static String time(Long millis) {
        return millis == null
            ? "—"
            : DateTimeFormatter.ofPattern("HH:mm:ss")
                  .withZone(ZoneId.of("America/Sao_Paulo"))
                  .format(Instant.ofEpochMilli(millis));
    }
}
