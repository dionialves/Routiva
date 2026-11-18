package br.com.scriptping.hosts;

import br.com.scriptping.equipment.EquipmentProvider;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
public class HostAuthentication {
    private final HostRepository repository;
    private final HostSecrets secrets;
    private final List<EquipmentProvider> providers;
    public HostAuthentication(HostRepository repository, HostSecrets secrets, List<EquipmentProvider> providers) {
        this.repository = repository; this.secrets = secrets; this.providers = providers;
    }
    public record Input(String manufacturer, String login,
        @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY) String password,
        Integer port, boolean enabled, boolean tls) {
        @Override public String toString() { return "Host authentication input [redacted]"; }
    }
    public record View(String hostId, String name, String ip, String manufacturer, String login, Integer port,
                       boolean enabled, boolean passwordConfigured, String status, String message, boolean tls, Long testedAt) {}
    public record Result(boolean success, String message) {}
    public Map<String, String> connectionStatuses(List<ManagedHost> hosts) {
        var credentials = repository.credentials();
        var result = new HashMap<String, String>();
        for (var h : hosts) result.put(h.id(), connectionStatus(h, credentials.get(h.id())));
        return result;
    }
    static String connectionStatus(ManagedHost host, HostRepository.Credentials credentials) {
        if (credentials == null || credentials.encryptedPassword() == null || credentials.login() == null || credentials.login().isBlank())
            return "NOT_CONFIGURED";
        String address = host.useIp() ? host.ip() : host.dns();
        return "ACTIVE".equals(credentials.status()) && Objects.equals(credentials.verifiedIp(), address) ? "ACTIVE" : "ERROR";
    }
    private EquipmentProvider provider(String name) {
        if (!Set.of("MikroTik", "Ubiquiti").contains(Objects.toString(name, "")))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Selecione MikroTik ou Ubiquiti.");
        return providers.stream().filter(p -> p.manufacturer().equals(name)).findFirst().orElseThrow(() ->
            new ResponseStatusException(HttpStatus.BAD_REQUEST, "Fabricante não suportado."));
    }
    public View view(String id) {
        var h = repository.target(id); var c = repository.credentials(id);
        boolean stale = c != null && "ACTIVE".equals(c.status()) && !Objects.equals(c.verifiedIp(), h.ip());
        return new View(id, h.name(), h.ip(), c == null ? null : c.manufacturer(), c == null ? null : c.login(),
            c == null ? null : c.port(), c != null, c != null && c.encryptedPassword() != null,
            c == null ? "PENDING" : stale ? "CONNECTION_FAILED" : c.status(),
            c == null ? "Configure a autenticação do host." : stale ? "Endereço alterado. Teste e salve novamente a autenticação." : c.message(),
            c != null && c.tls(), c == null ? null : c.testedAt());
    }
    private EquipmentProvider.Connection connection(String id, Input input) {
        repository.require(id);
        provider(input.manufacturer());
        if (input.login() == null || input.login().isBlank() || input.login().length() > 255 || input.port() == null
            || input.port() < 1 || input.port() > 65535 || (input.password() != null && input.password().length() > 4096))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe usuário e porta válida (1–65535).");
        String password = input.password();
        if (password == null || password.isEmpty()) {
            var saved = repository.credentials(id);
            if (saved == null || saved.encryptedPassword() == null)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe a senha do equipamento.");
            password = secrets.decrypt(saved.encryptedPassword());
        }
        return new EquipmentProvider.Connection(input.login().trim(), password, input.port(), "MikroTik".equals(input.manufacturer()) && input.tls());
    }
    private Result attempt(String id, String manufacturer, EquipmentProvider.Connection connection) {
        try {
            provider(manufacturer).testConnection(repository.target(id), connection);
            return new Result(true, "Conexão validada com " + manufacturer + ".");
        } catch (RuntimeException ex) {
            return new Result(false, "Falha na conexão. Confira endereço, credenciais e permissões do equipamento.");
        }
    }
    public synchronized Result test(String id, Input input) { return attempt(id, input.manufacturer(), connection(id, input)); }
    public synchronized View save(String id, Input input) {
        var connection = connection(id, input);
        var target = repository.target(id);
        var result = attempt(id, input.manufacturer(), connection);
        repository.configure(id, new HostRepository.Credentials(input.manufacturer(), connection.login(), secrets.encrypt(connection.password()),
            connection.port(), true, result.success() ? "ACTIVE" : "CONNECTION_FAILED",
            result.message(), result.success() ? target.ip() : null, connection.tls(), System.currentTimeMillis()));
        return view(id);
    }
    public synchronized void validateSaved(String id) {
        var c = repository.credentials(id);
        if (c != null) {
            try { save(id, new Input(c.manufacturer(), c.login(), null, c.port(), true, c.tls())); }
            catch (ResponseStatusException | IllegalStateException ex) {
                repository.configure(id, new HostRepository.Credentials(c.manufacturer(), c.login(), c.encryptedPassword(), c.port(),
                    true, "CONNECTION_FAILED", "Autenticação inválida. Revise e salve o acesso nas configurações do host.", null, c.tls(), System.currentTimeMillis()));
            }
        }
    }
}
