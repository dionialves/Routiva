package br.com.scriptping.mikrotik;

import br.com.scriptping.equipment.EquipmentProvider;
import br.com.scriptping.zabbix.Host;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class MikrotikEquipmentProvider implements EquipmentProvider {
    private final MikrotikService service;
    public MikrotikEquipmentProvider(MikrotikService service) { this.service = service; }
    public String manufacturer() { return "MikroTik"; }
    public Set<String> capabilities() { return Set.of("overview", "interfaces", "traffic", "wireless"); }
    public Snapshot read(Host host, String section, String selectedInterface) {
        var result = service.query(host, section, selectedInterface);
        return new Snapshot(result.measuredAt(), result.rows(), result.warning());
    }
}
