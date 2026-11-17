package br.com.scriptping.maps;

import br.com.scriptping.ingestion.PingSample;
import br.com.scriptping.zabbix.ZabbixService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class MapPingController {
    private final ZabbixService inventory;
    private final NamedParameterJdbcTemplate jdbc;

    public MapPingController(ZabbixService inventory, NamedParameterJdbcTemplate jdbc) {
        this.inventory = inventory;
        this.jdbc = jdbc;
    }

    public record State(String id, String state, Long measuredAt) {}

    @PostMapping("/api/maps/ping-status")
    public ResponseEntity<List<State>> status(@RequestBody NetworkMapModels.Targets targets) {
        if (targets.hosts() == null || targets.groups() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Consulta inválida");
        var hosts = inventory.hosts().stream().filter(host -> targets.hosts().contains(host.id())
            || host.groups().stream().anyMatch(group -> targets.groups().contains(group.id()))).toList();
        long now = System.currentTimeMillis();
        var byId = hosts.stream().collect(java.util.stream.Collectors.toMap(host -> host.id(), host -> host));
        var latest = new HashMap<String, PingSample>();
        if (!hosts.isEmpty()) {
            jdbc.query("""
                SELECT id,host_id,target,measured_at,latency,error FROM (
                    SELECT id,host_id,target,measured_at,latency,error,
                        ROW_NUMBER() OVER (PARTITION BY host_id,target ORDER BY measured_at DESC,id DESC) AS rn
                    FROM ping_sample WHERE host_id IN (:ids) AND measured_at >= :since AND measured_at <= :now
                ) recent WHERE rn=1
                """, Map.of("ids", hosts.stream().map(host -> host.id()).toList(), "since", now - 60000, "now", now), rs -> {
                    var sample = new PingSample(rs.getLong("id"), rs.getString("host_id"), rs.getString("target"),
                        rs.getLong("measured_at"), (Double) rs.getObject("latency"), rs.getString("error"));
                    var host = byId.get(sample.hostId());
                    if (sample.target().equals(host.ip())) latest.put(host.id(), sample);
                });
        }
        var result = hosts.stream().map(host -> {
            var sample = latest.get(host.id());
            String state = sample == null || now - sample.measuredAt() > 10000
                ? "unknown" : sample.outcome() == PingSample.SUCCESS ? "online"
                : sample.outcome() == PingSample.LOSS ? "offline" : "unknown";
            return new State(host.id(), state, sample == null ? null : sample.measuredAt());
        }).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }
}
