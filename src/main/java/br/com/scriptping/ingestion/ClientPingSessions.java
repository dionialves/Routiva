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
        final long startedAt;
        long expiresAt;
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
    public synchronized View read(String id, String host, String owner) {
        expire(System.currentTimeMillis());
        var session = owned(id, host, owner);
        session.expiresAt = System.currentTimeMillis() + LEASE_MS;
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
        if (sessions.values().removeIf(s -> s.expiresAt <= now)) revision++;
    }
    synchronized long revision() { expire(System.currentTimeMillis()); return revision; }
    synchronized List<HostConfig> configurations(long interval, long timeout, double limit) {
        expire(System.currentTimeMillis());
        return sessions.values().stream().map(s -> HostConfig.newBuilder().setHostId(s.id).setTarget(s.ip)
            .setIntervalMs(interval).setTimeoutMs(timeout).setEnabled(true).setLatencyLimitMs(limit).build()).toList();
    }
    synchronized void accept(List<Batch> batches) {
        expire(System.currentTimeMillis());
        for (var batch : batches) for (var result : batch.getResultsList()) {
            var s = sessions.get(result.getHostId());
            long at = result.getScheduledAt() / 1000000;
            if (s == null || !s.ip.equals(result.getTarget()) || at < s.startedAt
                || at > System.currentTimeMillis() + 60000 || result.getRttNs() < 0
                || (s.samples.size() >= 120 && at < s.samples.firstKey())) continue;
            boolean success = result.getStatus() == Status.SUCCESS;
            boolean loss = result.getStatus() == Status.TIMEOUT || result.getStatus() == Status.ICMP_ERROR;
            s.samples.put(at, new Point(at, success ? result.getRttNs() / 1000000d : null,
                success ? Double.valueOf(0) : loss ? Double.valueOf(100) : null, result.getStatus().name()));
            while (s.samples.size() > 120) s.samples.pollFirstEntry();
        }
    }
}
