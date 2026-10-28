package br.com.scriptping;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import static br.com.scriptping.HostAvailabilityService.*;

/** Builds all selectable views in the background; HTTP only reads this publication. */
@Service
public class AvailabilityDashboard {
    static final List<Integer> MINUTES = List.of(5, 60, 480, 1440, 2880);
    private final MonitorService monitor;
    private final PacketLossAnalysisService analysis;
    public record Row(String id, String name, Metrics metrics, String ip, List<Host.Group> groups,
        String status, boolean fresh, boolean collectorHealthy) {}
    static Row row(Host host, Metrics metrics, PacketLossAnalysis current) {
        String status = !IpAddress.isValid(host.ip()) ? "SEM_IP" : host.acknowledgedIcmpProblem() ? "RECONHECIDO"
            : current.stats().measuredAt() == null ? "SEM_DADOS" : !current.collectorHealthy() ? "FALHA_COLETA"
            : !current.fresh() ? "ATRASADO" : current.level().name();
        return new Row(host.id(), host.name(), metrics, host.ip(), host.groups(), status, current.fresh(), current.collectorHealthy());
    }
    public record View(Report report, List<Row> hosts) {}
    public record Publication(List<Host.Group> groups, Map<String, Map<Integer, View>> views) {}
    private volatile Publication publication = new Publication(List.of(), Map.of());
    public AvailabilityDashboard(MonitorService monitor, PacketLossAnalysisService analysis) {
        this.monitor = monitor; this.analysis = analysis;
    }
    public Publication current() { return publication; }

    @Scheduled(fixedDelay = 10000)
    public void refresh() {
        var hosts = monitor.hosts();
        var snapshot = analysis.availabilitySnapshot(hosts);
        Map<String, Host.Group> groups = new TreeMap<>();
        Map<String, List<Host>> members = new HashMap<>();
        members.put("", hosts);
        for (Host host : hosts) for (var group : host.groups()) {
            groups.put(group.id(), group);
            members.computeIfAbsent(group.id(), k -> new ArrayList<>()).add(host);
        }
        Map<String, Map<Integer, View>> views = new HashMap<>();
        members.forEach((id, selected) -> {
            Map<Integer, View> periods = new HashMap<>();
            for (int minutes : MINUTES) {
                int key = minutes == 5 ? 0 : minutes / 60;
                var empty = snapshot.empty().get(key);
                var reports = selected.stream().map(h -> snapshot.hosts().get(h.id()).get(key)).toList();
                List<Row> rows = selected.stream().map(h -> row(h, snapshot.hosts().get(h.id()).get(key).total(), snapshot.indicators().get(h.id()))).toList();
                periods.put(minutes, new View(combine(reports, empty), rows));
            }
            views.put(id, Map.copyOf(periods));
        });
        publication = new Publication(groups.values().stream().sorted(Comparator.comparing(Host.Group::name,
            String.CASE_INSENSITIVE_ORDER)).toList(), Map.copyOf(views));
    }

    static Metrics sum(List<Metrics> metrics) {
        Counts counts = new Counts();
        for (Metrics m : metrics) {
            counts.h += m.healthy(); counts.d += m.degraded(); counts.p += m.lost(); counts.e += m.errors();
            if (m.latency() != null) counts.sum += m.latency() * (m.healthy() + m.degraded());
        }
        return counts.metrics();
    }
    static Report combine(List<Report> reports, Report empty) {
        if (reports.isEmpty()) return empty;
        List<Point> points = new ArrayList<>();
        for (int i = 0; i < empty.series().size(); i++) {
            int index = i;
            var point = empty.series().get(i);
            points.add(new Point(point.at(), point.from(), point.to(), point.partial(), point.provisional(),
                sum(reports.stream().map(r -> r.series().get(index).metrics()).toList())));
        }
        Long latest = reports.stream().map(Report::lastSampleAt).filter(Objects::nonNull).max(Long::compare).orElse(null);
        return new Report(empty.from(), empty.to(), empty.bucketMillis(),
            sum(reports.stream().map(Report::total).toList()), List.copyOf(points), empty.version(),
            empty.evaluatedAt(), latest, empty.retainedFrom(), empty.load());
    }
}
