package br.com.scriptping.availability;

import br.com.scriptping.zabbix.Host;


import java.util.*;
import org.springframework.stereotype.Service;
import static br.com.scriptping.availability.HostAvailabilityService.*;

/** Selection is served exclusively from the published in-memory generation. */
@Service
public class AvailabilityDashboard {
    static final List<Integer> MINUTES=java.util.stream.Stream.concat(AvailabilityPeriod.OPTIONS.stream().map(AvailabilityPeriod::minutes), java.util.stream.Stream.of(480)).toList();
    private final AvailabilitySnapshotCache cache;
    public record Row(String id,String name,Metrics metrics,String ip,List<Host.Group> groups,
        String status,boolean fresh,boolean collectorHealthy) {}
    public record View(Report report,List<Row> hosts) {}
    public record Result(List<Host.Group> groups,View view) {}
    public AvailabilityDashboard(AvailabilitySnapshotCache cache) { this.cache=cache; }
    public Result query(int minutes,String groupId) { return cache.query(minutes, groupId); }
}
