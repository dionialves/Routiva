package br.com.scriptping.mikrotik;

import br.com.scriptping.equipment.EquipmentProvider.Connection;
import br.com.scriptping.equipment.MappedEquipmentSessions;
import br.com.scriptping.zabbix.Host;
import java.io.IOException;
import java.util.function.LongSupplier;

/** RouterOS transport using the common mapped-equipment session lifecycle. */
final class MappedRouterSessions implements AutoCloseable {
    @FunctionalInterface interface Factory {
        RouterOsConnection open(Host host, Connection credentials) throws IOException;
    }
    @FunctionalInterface interface Query<T> { T read(RouterOsConnection api) throws IOException; }
    private final MappedEquipmentSessions<RouterOsConnection> sessions;
    MappedRouterSessions() {
        this((host, credentials) -> new RouterOsConnection(host.ip(), credentials.port(), credentials.tls(),
            credentials.login(), credentials.password()), System::currentTimeMillis);
    }
    MappedRouterSessions(Factory factory, LongSupplier clock) {
        sessions = new MappedEquipmentSessions<>(factory::open, clock,
            ex -> ex instanceof IOException && !(ex instanceof RouterOsConnection.CommandRejectedException));
    }
    <T> T read(Host host, Connection credentials, Query<T> query) throws IOException {
        try { return sessions.read(host, credentials, query::read); }
        catch (IOException | RuntimeException ex) { throw ex; }
        catch (Exception ex) { throw new IOException("Mapped RouterOS query failed", ex); }
    }
    void expireIdle() { sessions.expireIdle(); }
    @Override public void close() { sessions.close(); }
}
