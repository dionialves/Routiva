package br.com.scriptping.availability;

import br.com.scriptping.zabbix.Host;
import br.com.scriptping.zabbix.ZabbixService;
import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.server.ResponseStatusException;

/** HTTP readers capture exactly one generation and never access storage or the builder. */
@Service
public class AvailabilitySnapshotCache {
    private static final Logger LOG=LoggerFactory.getLogger("routiva.indicators");
    private final PacketLossAnalysisService builder;
    private final AvailabilitySnapshotRepository repository;
    private final ZabbixService monitor;
    private volatile Generation current;

    private record ViewKey(int minutes, String group) {}
    private static final class Generation {
        final AvailabilitySnapshot snapshot;
        final List<Host.Group> groups;
        final ConcurrentHashMap<ViewKey, AvailabilityDashboard.View> views=new ConcurrentHashMap<>();
        Generation(AvailabilitySnapshot snapshot) {
            this.snapshot=snapshot;
            groups=snapshot.hosts().stream().flatMap(h -> h.groups().stream()).distinct()
                .sorted(Comparator.comparing(Host.Group::name, String.CASE_INSENSITIVE_ORDER)).toList();
        }
    }

    public AvailabilitySnapshotCache(PacketLossAnalysisService builder, AvailabilitySnapshotRepository repository,
        ZabbixService monitor) {
        this.builder=builder; this.repository=repository; this.monitor=monitor;
    }

    @PostConstruct
    public synchronized void restore() {
        try {
            var saved=repository.load();
            if (saved!=null) {
                current=new Generation(saved);
                LOG.info("event=AVAILABILITY_RESTORED version={} hosts={} built_at={}", saved.version(), saved.hosts().size(), saved.builtAt());
            }
        } catch (RuntimeException e) {
            LOG.error("event=AVAILABILITY_RESTORE_FAILED next_cycle=rebuild", e);
        }
    }

    /** Independent scheduler: database reads never run on the aggregation thread. */
    @Scheduled(fixedDelayString="${indicators.availability.interval-ms:60000}",
        initialDelayString="${indicators.availability.initial-delay-ms:0}", scheduler="availabilityScheduler")
    public synchronized void refresh() {
        long started=System.nanoTime();
        try {
            var snapshot=builder.advance(List.copyOf(monitor.hosts()), current==null ? null : current.snapshot);
            if (snapshot==null || snapshot.load().evaluatedAt()==null) return;
            var next=new Generation(snapshot);
            var encoded=repository.encode(snapshot);
            repository.save(snapshot, encoded); // Proxy returns only after the durable commit.
            current=next;
            LOG.info("event=AVAILABILITY_PUBLISHED version={} hosts={} periods={} compressed_bytes={} total_ms={}",
                snapshot.version(), snapshot.hosts().size(), snapshot.periods().size(), encoded.bytes(),
                (System.nanoTime()-started)/1_000_000);
        } catch (RuntimeException e) {
            LOG.error("event=AVAILABILITY_REFRESH_FAILED previous_version=preserved", e);
        }
    }

    public AvailabilityDashboard.Result query(int minutes, String groupId) {
        if (!AvailabilityDashboard.MINUTES.contains(minutes))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Período inválido");
        var generation=current;
        if (generation==null) return new AvailabilityDashboard.Result(List.of(), null);
        if (!groupId.isEmpty() && generation.groups.stream().noneMatch(g -> g.id().equals(groupId)))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Grupo indisponível");
        var key=new ViewKey(minutes, groupId);
        var view=generation.views.get(key);
        if (view==null) {
            var hosts=generation.snapshot.hosts();
            var selected=groupId.isEmpty() ? hosts : hosts.stream()
                .filter(h -> h.groups().stream().anyMatch(g -> g.id().equals(groupId))).toList();
            view=generation.snapshot.view(minutes, selected);
            // Bound derived views; the immutable per-host generation always remains available.
            synchronized (generation.views) {
                var previous=generation.views.get(key);
                if (previous!=null) view=previous;
                else if (generation.views.size()<128) generation.views.put(key, view);
            }
        }
        return new AvailabilityDashboard.Result(generation.groups, view);
    }

    public HostAvailabilityService.Report host(String id, int minutes) {
        var generation=current;
        if (generation==null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Carregando indicadores");
        var host=generation.snapshot.hosts().stream().filter(h -> h.id().equals(id)).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return generation.snapshot.view(minutes, List.of(host)).report();
    }
}
