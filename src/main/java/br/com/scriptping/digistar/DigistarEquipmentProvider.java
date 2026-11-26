package br.com.scriptping.digistar;

import br.com.scriptping.equipment.EquipmentProvider;
import br.com.scriptping.ubiquiti.UbiquitiSsh;
import br.com.scriptping.zabbix.Host;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class DigistarEquipmentProvider implements EquipmentProvider {
    private final UbiquitiSsh ssh;

    public DigistarEquipmentProvider(UbiquitiSsh ssh) {
        this.ssh = ssh;
    }

    @Override public String manufacturer() { return "Digistar"; }
    @Override public Set<String> capabilities() { return Set.of(); }

    @Override
    public void testConnection(Host host, Connection connection) {
        try (var ignored = ssh.connect(host.ip(), connection)) {
            // Establishing the authenticated SSH connection validates the saved credentials.
        } catch (com.jcraft.jsch.JSchException ex) {
            throw new IllegalStateException("Falha na conexão SSH com a Digistar.", ex);
        }
    }

    @Override
    public Snapshot read(Host host, String section, String selectedInterface) {
        throw new UnsupportedOperationException("A Digistar oferece backup pelo menu Backup.");
    }
}
