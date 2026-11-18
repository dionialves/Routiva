package br.com.scriptping.mikrotik;

import br.com.scriptping.equipment.EquipmentProvider.Connection;
import br.com.scriptping.zabbix.Host;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/** Sessions shared by mapped-interface readers; no autonomous equipment polling. */
final class MappedRouterSessions implements AutoCloseable {
    static final long IDLE_MS = 45_000;
    @FunctionalInterface interface Factory {
        RouterOsConnection open(Host host, Connection credentials) throws IOException;
    }
    @FunctionalInterface interface Query<T> { T read(RouterOsConnection api) throws IOException; }
    private record Session(String address, Connection credentials, RouterOsConnection api, long usedAt) {}
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final Factory factory;
    private final LongSupplier clock;
    private volatile boolean closed;

    MappedRouterSessions() {
        this((host, credentials) -> new RouterOsConnection(host.ip(), credentials.port(), credentials.tls(),
            credentials.login(), credentials.password()), System::currentTimeMillis);
    }
    MappedRouterSessions(Factory factory, LongSupplier clock) { this.factory = factory; this.clock = clock; }

    <T> T read(Host host, Connection credentials, Query<T> query) throws IOException {
        var result = new AtomicReference<T>();
        var failure = new AtomicReference<IOException>();
        var invalid = new AtomicReference<RuntimeException>();
        // compute serializes an entire query for this equipment, including all its interfaces.
        sessions.compute(host.id(), (id, previous) -> {
            if (closed) { disconnect(previous); failure.set(new IOException("Session manager closed")); return null; }
            Session session = previous;
            if (session != null && (!session.address().equals(host.ip()) || !session.credentials().equals(credentials)
                    || clock.getAsLong() - session.usedAt() >= IDLE_MS)) {
                disconnect(session); session = null;
            }
            boolean reused = session != null;
            try {
                if (session == null) session = open(host, credentials);
                try {
                    result.set(query.read(session.api()));
                } catch (IOException ex) {
                    disconnect(session); session = null;
                    // A reused socket may have been closed by the router while idle. Retry read-only queries once.
                    if (!reused || ex instanceof RouterOsConnection.CommandRejectedException) throw ex;
                    session = open(host, credentials);
                    result.set(query.read(session.api()));
                }
                return new Session(host.ip(), credentials, session.api(), clock.getAsLong());
            } catch (IOException ex) {
                disconnect(session); failure.set(ex); return null;
            } catch (RuntimeException ex) {
                disconnect(session); invalid.set(ex); return null;
            }
        });
        if (failure.get() != null) throw failure.get();
        if (invalid.get() != null) throw invalid.get();
        return result.get();
    }
    private Session open(Host host, Connection credentials) throws IOException {
        return new Session(host.ip(), credentials, factory.open(host, credentials), clock.getAsLong());
    }
    void expireIdle() {
        sessions.forEach((id, ignored) -> sessions.computeIfPresent(id, (key, session) -> {
            if (clock.getAsLong() - session.usedAt() < IDLE_MS) return session;
            disconnect(session); return null;
        }));
    }
    private static void disconnect(Session session) {
        if (session != null) try { session.api().close(); } catch (IOException ignored) { }
    }
    @Override public void close() {
        closed = true;
        sessions.forEach((id, ignored) -> sessions.computeIfPresent(id, (key, session) -> {
            disconnect(session); return null;
        }));
    }
}
