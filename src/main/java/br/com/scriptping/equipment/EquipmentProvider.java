package br.com.scriptping.equipment;

import br.com.scriptping.zabbix.Host;
import java.util.*;

/** Manufacturer adapters expose the same sections to equipment pages. */
public interface EquipmentProvider {
    String manufacturer();
    Set<String> capabilities();
    Snapshot read(Host host, String section, String selectedInterface);
    record Snapshot(long measuredAt, List<Map<String, String>> rows, String warning) {}
}
