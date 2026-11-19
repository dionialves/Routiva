package br.com.scriptping.equipment;

import br.com.scriptping.equipment.EquipmentProvider.Connection;
import br.com.scriptping.zabbix.Host;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/** One serialized, demand-driven session per host, shared by all mapped interfaces and roles. */
public final class MappedEquipmentSessions<T extends AutoCloseable> implements AutoCloseable {
    public static final long IDLE_MS = 45_000;
    @FunctionalInterface public interface Factory<T> { T open(Host host, Connection credentials) throws Exception; }
    @FunctionalInterface public interface Query<T, R> { R read(T transport) throws Exception; }
    private record Session<T>(String address, Connection credentials, T transport, long usedAt) {}
    private final ConcurrentHashMap<String, Session<T>> sessions = new ConcurrentHashMap<>();
    private final Semaphore active = new Semaphore(8);
    private final Factory<T> factory;
    private final LongSupplier clock;
    private final Predicate<Exception> retryable;
    private volatile boolean closed;

    public MappedEquipmentSessions(Factory<T> factory, Predicate<Exception> retryable) {
        this(factory, System::currentTimeMillis, retryable);
    }
    public MappedEquipmentSessions(Factory<T> factory, LongSupplier clock, Predicate<Exception> retryable) {
        this.factory = factory; this.clock = clock; this.retryable = retryable;
    }
    public <R> R read(Host host, Connection credentials, Query<T, R> query) throws Exception {
        var result = new AtomicReference<R>();
        var failure = new AtomicReference<Exception>();
        // Queue behind this host before taking capacity: ten interfaces still use one session/permit.
        sessions.compute(host.id(), (id, previous) -> {
            if (closed) { disconnect(previous); failure.set(new IOException("Session manager closed")); return null; }
            Session<T> session = previous;
            if (session != null && (!session.address().equals(host.ip()) || !session.credentials().equals(credentials)
                    || clock.getAsLong() - session.usedAt() >= IDLE_MS)) {
                disconnect(session); session = null;
            }
            boolean acquired = false;
            try {
                active.acquire(); acquired = true;
                if (closed) throw new IOException("Session manager closed");
                boolean reused = session != null;
                if (session == null) session = open(host, credentials);
                try { result.set(query.read(session.transport())); }
                catch (Exception ex) {
                    disconnect(session); session = null;
                    if (!reused || !retryable.test(ex)) throw ex;
                    session = open(host, credentials);
                    result.set(query.read(session.transport()));
                }
                return new Session<>(host.ip(), credentials, session.transport(), clock.getAsLong());
            } catch (Exception ex) {
                if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
                disconnect(session); failure.set(ex); return null;
            } finally { if (acquired) active.release(); }
        });
        if (failure.get() != null) throw failure.get();
        return result.get();
    }
    private Session<T> open(Host host, Connection credentials) throws Exception {
        return new Session<>(host.ip(), credentials, factory.open(host, credentials), clock.getAsLong());
    }
    public void expireIdle() {
        sessions.forEach((id, ignored) -> sessions.computeIfPresent(id, (key, session) -> {
            if (clock.getAsLong() - session.usedAt() < IDLE_MS) return session;
            disconnect(session); return null;
        }));
    }
    private static void disconnect(Session<? extends AutoCloseable> session) {
        if (session != null) try { session.transport().close(); } catch (Exception ignored) { }
    }
    @Override public void close() {
        closed = true;
        sessions.forEach((id, ignored) -> sessions.computeIfPresent(id, (key, session) -> {
            disconnect(session); return null;
        }));
    }
}
