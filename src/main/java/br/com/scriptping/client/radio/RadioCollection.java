package br.com.scriptping.client.radio;

import java.util.*;

/** Captured task data. Contains no sessions, worker, network access or persistence. */
public final class RadioCollection {
    private RadioCollection() {}
    public record Sample(long at, String mac, Map<String, String> raw, List<String> observedLogins) {}
    public record Latency(String mac, long at, Map<String, Object> data) {}
    public record Batch(String id, String host, String hostName, String owner, long startedAt,
                        long touchedAt, List<Sample> samples, List<Latency> tests) {}
}
