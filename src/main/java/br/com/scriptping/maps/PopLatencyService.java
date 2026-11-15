package br.com.scriptping.maps;

import br.com.scriptping.zabbix.Host;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class PopLatencyService {
    private final NamedParameterJdbcTemplate jdbc;
    private final double limit;

    public PopLatencyService(NamedParameterJdbcTemplate jdbc,
        @Value("${monitor.latency-limit-ms:20}") double limit) {
        this.jdbc = jdbc;
        this.limit = limit;
    }

    public record Reading(String id, String name, Double latency, String state, Long updatedAt) {}
    public record Summary(Double average, Double maximum, int aboveLimit, String state,
        Long updatedAt, List<Reading> hosts) {}

    public Map<String, Summary> summaries(Set<String> groups, Set<String> hosts, List<Host> inventory) {
        var selected = inventory.stream().filter(h -> hosts.contains(h.id())
            || h.groups().stream().anyMatch(g -> groups.contains(g.id()))).toList();
        Map<String, Reading> readings = new HashMap<>();
        if (!selected.isEmpty()) {
            jdbc.query("""
                SELECT host_id,latency,measured_at FROM (
                    SELECT host_id,latency,measured_at,
                        ROW_NUMBER() OVER (PARTITION BY host_id ORDER BY measured_at DESC,id DESC) AS rn
                    FROM ping_sample WHERE host_id IN (:ids) AND measured_at >= :since
                ) latest WHERE rn=1
                """, Map.of("ids", selected.stream().map(Host::id).toList(),
                    "since", System.currentTimeMillis() - 90000), rs -> {
                    Double value = (Double) rs.getObject("latency");
                    if (value != null && (!Double.isFinite(value) || value < 0)) value = null;
                    String id = rs.getString("host_id");
                    readings.put(id, new Reading(id, "", value, state(value), rs.getLong("measured_at")));
                });
        }
        Map<String, Summary> result = new HashMap<>();
        for (String id : groups) result.put("group:" + id, summarize(selected.stream()
            .filter(h -> h.groups().stream().anyMatch(g -> g.id().equals(id))).toList(), readings));
        for (String id : hosts) result.put("host:" + id, summarize(selected.stream()
            .filter(h -> h.id().equals(id)).toList(), readings));
        return result;
    }

    String state(Double value) {
        return value == null ? "SEM_DADOS" : value > limit ? "CRITICO"
            : value >= limit * 0.75 ? "ATENCAO" : "NORMAL";
    }

    Summary summarize(List<Host> hosts, Map<String, Reading> readings) {
        var rows = hosts.stream().map(h -> {
            var r = readings.get(h.id());
            return new Reading(h.id(), h.name(), r == null ? null : r.latency(),
                r == null ? "SEM_DADOS" : r.state(), r == null ? null : r.updatedAt());
        }).distinct().sorted(Comparator.comparing(Reading::latency,
            Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(Reading::name)).toList();
        var values = rows.stream().filter(r -> r.latency() != null).mapToDouble(Reading::latency).summaryStatistics();
        Double maximum = values.getCount() == 0 ? null : values.getMax();
        return new Summary(values.getCount() == 0 ? null : values.getAverage(), maximum,
            (int) rows.stream().filter(r -> "CRITICO".equals(r.state())).count(), state(maximum),
            rows.stream().map(Reading::updatedAt).filter(Objects::nonNull).max(Long::compareTo).orElse(null),
            rows.stream().limit(3).toList());
    }
}
