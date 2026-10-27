package br.com.scriptping;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Publishes complete database-derived snapshots; readers never trigger analysis or SQL. */
@Service
public class PacketLossAnalysisService {

    private record Key(String hostId, String target) {}

    private final SampleStore store;
    private final PacketLossAnalyzer analyzer;
    private final PacketLossAnalysis empty;
    private volatile Map<Key, PacketLossAnalysis> snapshot = Map.of();

    public PacketLossAnalysisService(
        SampleStore store,
        PacketLossPolicy rules,
        @Value("${monitor.interval-ms}") long interval,
        @Value("${monitor.timeout-ms}") long timeout
    ) {
        this.store = store;
        analyzer = new PacketLossAnalyzer(rules, interval, timeout);
        empty = analyzer.analyze(new RollingPingHistory(), 0);
    }

    @Scheduled(fixedRate = 10000, scheduler = "indicatorScheduler")
    public void refresh() {
        refreshAt(System.currentTimeMillis());
    }

    // A failed refresh leaves the last complete snapshot available to readers.
    synchronized void refreshAt(long now) {
        Map<Key, RollingPingHistory> histories = new HashMap<>();
        store.readRecent(now - analyzer.lookbackMs(), sample -> {
            if (!IpAddress.isValid(sample.target()) || sample.measuredAt() > now) return;
            histories.computeIfAbsent(new Key(sample.hostId(), sample.target()), key ->
                new RollingPingHistory()
            ).add(sample);
        });
        Map<Key, PacketLossAnalysis> next = new HashMap<>();
        histories.forEach((key, history) -> next.put(key, analyzer.analyze(history, now)));
        snapshot = Map.copyOf(next);
    }

    public Map<String, PacketLossAnalysis> current(List<Host> hosts) {
        var available = snapshot;
        Map<String, PacketLossAnalysis> result = new HashMap<>();
        for (Host host : hosts) {
            result.put(host.id(), available.getOrDefault(new Key(host.id(), host.ip()), empty));
        }
        return result;
    }
}
