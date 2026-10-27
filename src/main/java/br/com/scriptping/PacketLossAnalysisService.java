package br.com.scriptping;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/** Restores once, then reuses samples published by the collector; dashboard reads do not scan SQL. */
@Service
public class PacketLossAnalysisService {

    private record Key(String hostId, String target) {}

    private static class Bucket {

        final RollingPingHistory history = new RollingPingHistory();
        PacketLossAnalysis cached;
        long evaluatedAt = -1,
            version = -1;
    }

    private final Map<Key, Bucket> histories = new ConcurrentHashMap<>();
    private final SampleStore store;
    private final PacketLossPolicy rules;
    private final PacketLossAnalyzer analyzer;

    public PacketLossAnalysisService(
        SampleStore store,
        PacketLossPolicy rules,
        @Value("${monitor.interval-ms}") long interval,
        @Value("${monitor.timeout-ms}") long timeout
    ) {
        this.store = store;
        this.rules = rules;
        analyzer = new PacketLossAnalyzer(rules, interval, timeout);
    }

    @PostConstruct
    public void restore() {
        long cutoff = System.currentTimeMillis() - analyzer.lookbackMs();
        Set<Key> invalid = new HashSet<>();
        store.readRecent(cutoff, sample -> {
            Key key = new Key(sample.hostId(), sample.target());
            if (invalid.contains(key)) return;
            Bucket bucket = histories.get(key);
            if (bucket == null) {
                if (!IpAddress.isValid(sample.target())) {
                    invalid.add(key);
                    return;
                }
                bucket = new Bucket();
                histories.put(key, bucket);
            }
            bucket.history.add(sample);
        });
    }

    @EventListener
    public void accept(PingSample sample) {
        if (!IpAddress.isValid(sample.target())) return;
        long now = System.currentTimeMillis();
        Bucket bucket = histories.computeIfAbsent(new Key(sample.hostId(), sample.target()), k ->
            new Bucket()
        );
        synchronized (bucket) {
            if (bucket.history.add(sample)) evaluate(bucket, now, true);
        }
    }

    private PacketLossAnalysis evaluate(Bucket bucket, long now, boolean force) {
        bucket.history.prune(now - analyzer.lookbackMs());
        if (
            !force &&
            bucket.cached != null &&
            bucket.version == bucket.history.version() &&
            now >= bucket.evaluatedAt &&
            now - bucket.evaluatedAt < rules.getCacheMillis()
        ) return bucket.cached;
        PacketLossAnalysis result = analyzer.analyze(bucket.history, now);
        bucket.cached = result;
        bucket.version = bucket.history.version();
        bucket.evaluatedAt = now;
        return result;
    }

    public Map<String, PacketLossAnalysis> analyze(List<Host> hosts, long now) {
        Map<String, PacketLossAnalysis> result = new HashMap<>();
        for (Host host : hosts) {
            Bucket bucket = histories.computeIfAbsent(new Key(host.id(), host.ip()), k ->
                new Bucket()
            );
            synchronized (bucket) {
                result.put(host.id(), evaluate(bucket, now, false));
            }
        }
        return result;
    }

    public void retainHosts(List<Host> hosts) {
        Set<Key> active = new HashSet<>();
        for (Host host : hosts) active.add(new Key(host.id(), host.ip()));
        histories.keySet().removeIf(key -> !active.contains(key));
    }
}
