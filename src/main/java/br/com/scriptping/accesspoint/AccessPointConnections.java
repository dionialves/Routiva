package br.com.scriptping.accesspoint;

import br.com.scriptping.equipment.EquipmentProvider.Connection;
import br.com.scriptping.zabbix.Host;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Component
public class AccessPointConnections {
    private final AccessPointRepository repository;
    private final AccessPointSecrets secrets;
    public AccessPointConnections(AccessPointRepository repository, AccessPointSecrets secrets) {
        this.repository = repository; this.secrets = secrets;
    }
    public Connection require(Host host) {
        var c = repository.configuration(host.id());
        if (c == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Host não definido como AP.");
        if (!c.enabled() || !"ACTIVE".equals(c.status()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "AP inativo, com configuração pendente ou falha na conexão. Abra a configuração do AP.");
        if (!java.util.Objects.equals(c.verifiedIp(), host.ip())) {
            repository.connectionState(host.id(), "CONNECTION_FAILED", "IP alterado no Zabbix. Salve a configuração para testar novamente.", null);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "IP alterado. Teste novamente a configuração do AP.");
        }
        return new Connection(c.login(), secrets.decrypt(c.encryptedPassword()), c.port(), c.tls());
    }
}
