package br.com.scriptping;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static br.com.scriptping.HostAvailabilityService.*;

/** Queries only the requested group and period. No background publication or cache. */
@Service
public class AvailabilityDashboard {
    static final List<Integer> MINUTES=List.of(5,60,480,1440,2880);
    private final MonitorService monitor;
    private final PacketLossAnalysisService analysis;
    public record Row(String id,String name,Metrics metrics,String ip,List<Host.Group> groups,
        String status,boolean fresh,boolean collectorHealthy) {}
    public record View(Report report,List<Row> hosts) {}
    public record Result(List<Host.Group> groups,View view) {}
    public AvailabilityDashboard(MonitorService monitor,PacketLossAnalysisService analysis) {
        this.monitor=monitor;this.analysis=analysis;
    }
    public Result query(int minutes,String groupId) {
        var hosts=monitor.hosts();
        var groups=hosts.stream().flatMap(h->h.groups().stream()).distinct()
            .sorted(Comparator.comparing(Host.Group::name,String.CASE_INSENSITIVE_ORDER)).toList();
        if(!groupId.isEmpty() && groups.stream().noneMatch(g->g.id().equals(groupId)))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Grupo indisponível");
        var selected=groupId.isEmpty()?hosts:hosts.stream().filter(h->h.groups().stream().anyMatch(g->g.id().equals(groupId))).toList();
        return new Result(groups,analysis.view(selected,minutes));
    }
}
