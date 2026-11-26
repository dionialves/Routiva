package br.com.scriptping.hosts;

import br.com.scriptping.equipment.EquipmentProvider.Connection;
import br.com.scriptping.zabbix.Host;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Component
public class HostConnections {
    private final HostRepository repository;
    private final HostSecrets secrets;
    public HostConnections(HostRepository repository, HostSecrets secrets) { this.repository = repository; this.secrets = secrets; }
    public Connection require(Host host) {
        repository.require(host.id());
        return require(host, repository.credentials(host.id()));
    }

    public Connection require(Host host, HostRepository.Credentials c) {
        if (c == null || !"ACTIVE".equals(c.status()) || !java.util.Objects.equals(c.verifiedIp(), host.ip()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Configure e valide a autenticação nas configurações do host.");
        int port = HostAuthentication.usesSsh(c.manufacturer()) ? repository.terminalPorts(host.id()).sshPort() : c.port();
        return new Connection(c.login(), secrets.decrypt(c.encryptedPassword()), port, c.tls());
    }
}
