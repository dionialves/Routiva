package br.com.scriptping.equipment;

import br.com.scriptping.zabbix.Host;
import java.util.*;

/** Manufacturer adapters expose the same sections to equipment pages. */
public interface EquipmentProvider {
    String manufacturer();
    Set<String> capabilities();
    Snapshot read(Host host, String section, String selectedInterface);
    void testConnection(Host host, Connection connection);
    record Connection(String login, @com.fasterxml.jackson.annotation.JsonIgnore String password, int port, boolean tls) {
        public Connection(String login, String password, int port) { this(login, password, port, false); }
        @Override public String toString() { return "AP connection [redacted]"; }
    }
    record Snapshot(long measuredAt, List<Map<String, String>> rows, String warning) {}
}
