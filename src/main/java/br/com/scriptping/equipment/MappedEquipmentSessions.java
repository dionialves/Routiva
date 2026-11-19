package br.com.scriptping.equipment;

import br.com.scriptping.equipment.EquipmentProvider.Connection;
import br.com.scriptping.zabbix.Host;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/** One serialized, demand-driven session per host, shared by all mapped interfaces and roles. */
public final class MappedEquipmentSessions<T extends AutoCloseable> implements AutoCloseable {
    public static final long IDLE_MS = 45_000;
    @FunctionalInterface public interface Factory<T> { T open(Host host, Connection credentials) throws Exception; }
    @FunctionalInterface public interface Query<T, R> { R read(T transport) throws Exception; }
    private record Session<T>(String address, Connection credentials, T transport, long usedAt) {}
    private static final class HostSession<T> {
        final ReentrantLock lock = new ReentrantLock();
        Session<T> session;
        int users;
    }
    private final ConcurrentHashMap<String, HostSession<T>> sessions = new ConcurrentHashMap<>();
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
        var holder = sessions.compute(host.id(), (id, existing) -> {
            var value = existing == null ? new HostSession<T>() : existing;
            value.users++;
            return value;
        });
        // Never wait or perform I/O inside ConcurrentHashMap.compute: its monitor pins Java 21
        // virtual threads. Queue per host before taking global query capacity.
        holder.lock.lock();
        try {
            if (closed) throw new IOException("Session manager closed");
            Session<T> session = holder.session;
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
                R result;
                try { result = query.read(session.transport()); }
                catch (Exception ex) {
                    disconnect(session); session = null;
                    if (!reused || !retryable.test(ex)) throw ex;
                    session = open(host, credentials);
                    result = query.read(session.transport());
                }
                holder.session = new Session<>(host.ip(), credentials, session.transport(), clock.getAsLong());
                return result;
            } catch (Exception ex) {
                if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
                disconnect(session); holder.session = null; throw ex;
            } finally { if (acquired) active.release(); }
        } finally {
            holder.lock.unlock();
            release(host.id());
        }
    }
    private Session<T> open(Host host, Connection credentials) throws Exception {
        return new Session<>(host.ip(), credentials, factory.open(host, credentials), clock.getAsLong());
    }
    private void release(String id) {
        sessions.computeIfPresent(id, (key, current) -> {
            current.users--;
            return current.users == 0 && current.session == null ? null : current;
        });
    }
    private void clean(boolean all) {
        sessions.forEach((id, ignored) -> {
            var holder = sessions.computeIfPresent(id, (key, current) -> {
                current.users++;
                return current;
            });
            if (holder == null) return;
            holder.lock.lock();
            try {
                var session = holder.session;
                if (session != null && (all || clock.getAsLong() - session.usedAt() >= IDLE_MS)) {
                    disconnect(session);
                    holder.session = null;
                }
            } finally {
                holder.lock.unlock();
                release(id);
            }
        });
    }
    public void expireIdle() {
        clean(false);
    }
    private static void disconnect(Session<? extends AutoCloseable> session) {
        if (session != null) try { session.transport().close(); } catch (Exception ignored) { }
    }
    @Override public void close() {
        closed = true;
        clean(true);
    }
}
