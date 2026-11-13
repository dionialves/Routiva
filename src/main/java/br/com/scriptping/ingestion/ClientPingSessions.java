package br.com.scriptping.ingestion;

import br.com.scriptping.IpAddress;
import br.com.scriptping.collector.proto.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Short-lived client probes. Raw samples use normal ingestion/cleanup, never host history. */
@Component
public class ClientPingSessions {
    public static final String PREFIX = "client-live:";
    private static final long LEASE_MS = 15000;
    public record Point(long at, Double latency, Double loss, String status) {}
    public record View(String id, String ip, long startedAt, List<Point> samples) {}
    private static final class Session {
        final String id = PREFIX + UUID.randomUUID(), host, owner, ip;
        long startedAt;
        long expiresAt;
        long endsAt = Long.MAX_VALUE;
        boolean finished;
        int packetLimit;
        final NavigableMap<Long, Point> samples = new TreeMap<>();
        Session(String host, String owner, String ip, long now) {
            this.host = host; this.owner = owner; this.ip = ip; startedAt = now; expiresAt = now + LEASE_MS;
        }
        View view() { return new View(id, ip, startedAt, List.copyOf(samples.values())); }
    }
    private final Map<String, Session> sessions = new HashMap<>();
    private long revision;

    public static boolean isIpv4(String ip) {
        return IpAddress.isValid(ip) && !ip.contains(":")
            && Arrays.stream(ip.split("\\.")).allMatch(part -> part.equals("0") || !part.startsWith("0"));
    }
    public synchronized View start(String host, String owner, String ip) {
        if (!isIpv4(ip))
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "IPv4 válido não encontrado no IXC.");
        expire(System.currentTimeMillis());
        if (sessions.size() >= 256) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Limite de diagnósticos simultâneos atingido.");
        var session = new Session(host, owner, ip, System.currentTimeMillis());
        sessions.put(session.id, session); revision++;
        return session.view();
    }
    public synchronized View startWindow(String host, String owner, String ip, long startedAt, long endsAt) {
        var view = start(host, owner, ip);
        var session = sessions.get(view.id());
        session.startedAt = startedAt;
        session.endsAt = endsAt;
        session.expiresAt = endsAt + 300000;
        return session.view();
    }
    public synchronized View startPackets(String host, String owner, String ip, int packets, long deadline) {
        if (!Set.of(5, 10, 30, 60).contains(packets))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Selecione 5, 10, 30 ou 60 pacotes.");
        var view = startWindow(host, owner, ip, System.currentTimeMillis(), deadline);
        sessions.get(view.id()).packetLimit = packets;
        return view;
    }
    public synchronized View read(String id, String host, String owner) {
        expire(System.currentTimeMillis());
        var session = owned(id, host, owner);
        if (session.endsAt == Long.MAX_VALUE) session.expiresAt = System.currentTimeMillis() + LEASE_MS;
        return session.view();
    }
    public synchronized void stop(String id, String host, String owner) {
        var session = sessions.get(id);
        if (session == null) return;
        owned(id, host, owner); sessions.remove(id); revision++;
    }
    private Session owned(String id, String host, String owner) {
        var session = sessions.get(id);
        if (session == null || !session.host.equals(host) || !session.owner.equals(owner))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Sessão de ping encerrada.");
        return session;
    }
    synchronized void expire(long now) {
        for (var session : sessions.values()) {
            if (!session.finished && session.endsAt <= now) { session.finished = true; revision++; }
        }
        if (sessions.values().removeIf(s -> s.expiresAt <= now)) revision++;
    }
    synchronized long revision() { expire(System.currentTimeMillis()); return revision; }
    synchronized List<HostConfig> configurations(long interval, long timeout, double limit) {
        expire(System.currentTimeMillis());
        return sessions.values().stream().filter(s -> !s.finished).map(s -> HostConfig.newBuilder().setHostId(s.id).setTarget(s.ip)
            .setIntervalMs(s.endsAt == Long.MAX_VALUE ? interval : 1000).setTimeoutMs(s.endsAt == Long.MAX_VALUE ? timeout : 1500).setMaxPackets(s.packetLimit).setEnabled(true).setLatencyLimitMs(limit).build()).toList();
    }
    synchronized void accept(List<Batch> batches) {
        expire(System.currentTimeMillis());
        for (var batch : batches) for (var result : batch.getResultsList()) {
            var s = sessions.get(result.getHostId());
            long at = result.getScheduledAt() / 1000000;
            if (s == null || !s.ip.equals(result.getTarget()) || at < s.startedAt || at >= s.endsAt
                || at > System.currentTimeMillis() + 60000 || result.getRttNs() < 0
                || (s.samples.size() >= 120 && at < s.samples.firstKey())) continue;
            if (s.packetLimit > 0 && (result.getStatus() == Status.SKIPPED
                || (s.samples.size() >= s.packetLimit && !s.samples.containsKey(at)))) continue;
            boolean success = result.getStatus() == Status.SUCCESS;
            boolean loss = result.getStatus() == Status.TIMEOUT || result.getStatus() == Status.ICMP_ERROR;
            s.samples.put(at, new Point(at, success ? result.getRttNs() / 1000000d : null,
                success ? Double.valueOf(0) : loss ? Double.valueOf(100) : null, result.getStatus().name()));
            if (s.packetLimit > 0 && s.samples.size() >= s.packetLimit && !s.finished) {
                s.finished = true; revision++;
            }
            while (s.samples.size() > 120) s.samples.pollFirstEntry();
        }
    }
}
