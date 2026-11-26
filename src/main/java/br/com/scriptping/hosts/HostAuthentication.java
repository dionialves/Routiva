package br.com.scriptping.hosts;

import br.com.scriptping.equipment.EquipmentProvider;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
public class HostAuthentication {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(HostAuthentication.class);
    private static final String UNREADABLE_PASSWORD =
        "Não foi possível ler a senha cadastrada do equipamento. Confira a chave de proteção do app ou informe a senha novamente.";
    private final HostRepository repository;
    private final HostSecrets secrets;
    private final List<EquipmentProvider> providers;
    public HostAuthentication(HostRepository repository, HostSecrets secrets, List<EquipmentProvider> providers) {
        this.repository = repository; this.secrets = secrets; this.providers = providers;
    }
    public record Input(String manufacturer, String login,
        @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY) String password,
        Integer port, boolean enabled, boolean tls, Integer sshPort, Integer telnetPort) {
        public Input(String manufacturer, String login, String password, Integer port, boolean enabled, boolean tls) {
            this(manufacturer, login, password, port, enabled, tls, null, null);
        }
        @Override public String toString() { return "Host authentication input [redacted]"; }
    }
    public record View(String hostId, String name, String ip, String manufacturer, String login, Integer port,
                       boolean enabled, boolean passwordConfigured, String status, String message, boolean tls, Long testedAt, int sshPort, int telnetPort) {}
    public record TerminalAccess(String address, int sshPort, int telnetPort) {}

    public TerminalAccess terminalAccess(String id) {
        var host = repository.require(id);
        var ports = repository.terminalPorts(id);
        String address = host.ip();
        if (address == null || address.isBlank()) address = host.dns();
        return new TerminalAccess(address, ports.sshPort(), ports.telnetPort());
    }

    public synchronized HostRepository.TerminalPorts saveTerminalPorts(String id, HostRepository.TerminalPorts ports) {
        if (!validPort(ports.sshPort()) || !validPort(ports.telnetPort()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe portas entre 1 e 65535.");
        repository.updateTerminalPorts(id, ports);
        return ports;
    }

    public static boolean usesSsh(String manufacturer) {
        return "Ubiquiti".equals(manufacturer) || "Datacom".equals(manufacturer) || "Cisco".equals(manufacturer) || "Digistar".equals(manufacturer);
    }

    private HostRepository.TerminalPorts requestedPorts(String id, Input input) {
        var saved = repository.terminalPorts(id);
        Integer ssh = input.sshPort();
        // Compatibility with older clients that put the SSH port in the API port field.
        if (ssh == null && usesSsh(input.manufacturer())) ssh = input.port();
        var ports = new HostRepository.TerminalPorts(ssh == null ? saved.sshPort() : ssh,
            input.telnetPort() == null ? saved.telnetPort() : input.telnetPort());
        if (!validPort(ports.sshPort()) || !validPort(ports.telnetPort()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe portas entre 1 e 65535.");
        return ports;
    }

    private static boolean validPort(int port) { return port >= 1 && port <= 65535; }

    public record Result(boolean success, String message) {}
    public Map<String, String> connectionStatuses(List<ManagedHost> hosts) {
        var credentials = repository.credentials();
        var result = new HashMap<String, String>();
        for (var h : hosts) result.put(h.id(), connectionStatus(h, credentials.get(h.id())));
        return result;
    }
    static String connectionStatus(ManagedHost host, HostRepository.Credentials credentials) {
        if (credentials == null || credentials.manufacturer() == null || credentials.manufacturer().isBlank()
            || credentials.encryptedPassword() == null || credentials.login() == null || credentials.login().isBlank())
            return "NOT_CONFIGURED";
        String address = host.useIp() ? host.ip() : host.dns();
        return "ACTIVE".equals(credentials.status()) && Objects.equals(credentials.verifiedIp(), address) ? "ACTIVE" : "ERROR";
    }
    private EquipmentProvider provider(String name) {
        if (!Set.of("MikroTik", "Ubiquiti", "Datacom", "Mimosa", "Cisco", "Digistar").contains(Objects.toString(name, "")))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Selecione um Fabricante");
        return providers.stream().filter(p -> p.manufacturer().equals(name)).findFirst().orElseThrow(() ->
            new ResponseStatusException(HttpStatus.BAD_REQUEST, "Fabricante não suportado."));
    }
    public View view(String id) {
        var h = repository.target(id); var c = repository.credentials(id);
        var ports = repository.terminalPorts(id);
        boolean stale = c != null && "ACTIVE".equals(c.status()) && !Objects.equals(c.verifiedIp(), h.ip());
        return new View(id, h.name(), h.ip(), c == null ? null : c.manufacturer(), c == null ? null : c.login(),
            c == null || usesSsh(c.manufacturer()) ? null : c.port(), c != null, c != null && c.encryptedPassword() != null,
            c == null ? "PENDING" : stale ? "CONNECTION_FAILED" : c.status(),
            c == null ? "Configure a autenticação do host." : stale ? "Endereço alterado. Teste e salve novamente a autenticação." : c.message(),
            c != null && c.tls(), c == null ? null : c.testedAt(), ports.sshPort(), ports.telnetPort());
    }
    private EquipmentProvider.Connection connection(String id, Input input, HostRepository.TerminalPorts ports) {
        repository.require(id);
        provider(input.manufacturer());
        String login = "Mimosa".equals(input.manufacturer()) ? "configure" : input.login();
        Integer port = usesSsh(input.manufacturer()) ? Integer.valueOf(ports.sshPort()) : input.port();
        if (login == null || login.isBlank() || login.length() > 255 || port == null
            || !validPort(port) || (input.password() != null && input.password().length() > 4096))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe usuário e porta válida (1–65535).");
        return new EquipmentProvider.Connection(login.trim(), input.password(), port,
            "Mimosa".equals(input.manufacturer()) || "MikroTik".equals(input.manufacturer()) && input.tls());
    }

    private Result validateConnection(String id, String manufacturer, EquipmentProvider.Connection connection,
                                      HostRepository.Credentials saved) {
        if (connection.password() != null && !connection.password().isEmpty())
            return attempt(id, manufacturer, connection);
        if (saved == null || saved.encryptedPassword() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe a senha do equipamento.");

        String password;
        try {
            password = secrets.decrypt(saved.encryptedPassword());
        } catch (IllegalStateException ex) {
            return new Result(false, UNREADABLE_PASSWORD);
        }
        return attempt(id, manufacturer,
            new EquipmentProvider.Connection(connection.login(), password, connection.port(), connection.tls()));
    }

    private Result attempt(String id, String manufacturer, EquipmentProvider.Connection connection) {
        try {
            provider(manufacturer).testConnection(repository.target(id), connection);
            return new Result(true, "Conexão validada com " + manufacturer + ".");
        } catch (RuntimeException ex) {
            if ("Mimosa".equals(manufacturer) && ex instanceof ResponseStatusException response && response.getReason() != null)
                return new Result(false, response.getReason());
            return new Result(false, "Falha na conexão. Confira endereço, credenciais e permissões do equipamento.");
        }
    }
    public synchronized Result test(String id, Input input) {
        var connection = connection(id, input, requestedPorts(id, input));
        var saved = repository.credentials(id);
        var result = validateConnection(id, input.manufacturer(), connection, saved);
        if (!result.success() && saved != null)
            repository.markAuthenticationRejected(id, saved, null, result.message());
        if (UNREADABLE_PASSWORD.equals(result.message()))
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, UNREADABLE_PASSWORD);
        return result;
    }
    public synchronized View save(String id, Input input) {
        var ports = requestedPorts(id, input);
        if (input.manufacturer() == null || input.manufacturer().isEmpty()) {
            if (input.login() != null && !input.login().isBlank() || input.password() != null && !input.password().isEmpty())
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Selecione um fabricante.");
            repository.configure(id, null, ports);
            return view(id);
        }
        var connection = connection(id, input, ports);
        var target = repository.target(id);
        var saved = repository.credentials(id);
        var result = validateConnection(id, input.manufacturer(), connection, saved);
        String encryptedPassword = connection.password() == null || connection.password().isEmpty()
            ? saved.encryptedPassword() : secrets.encrypt(connection.password());
        repository.configure(id, new HostRepository.Credentials(input.manufacturer(), connection.login(), encryptedPassword,
            usesSsh(input.manufacturer()) ? null : connection.port(), true, result.success() ? "ACTIVE" : "CONNECTION_FAILED",
            result.message(), result.success() ? target.ip() : null, connection.tls(), System.currentTimeMillis()), ports);
        if (!result.success()) {
            LOGGER.warn("Host {} ({}) salvo com erro de conexão. {}",
                id, input.manufacturer(), UNREADABLE_PASSWORD.equals(result.message()) ? UNREADABLE_PASSWORD
                    : "Falha ao validar o acesso ao equipamento. Confira endereço, credenciais e permissões.");
        }
        return view(id);
    }
    public synchronized void validateSaved(String id) {
        var c = repository.credentials(id);
        if (c != null) {
            try { save(id, new Input(c.manufacturer(), c.login(), null, c.port(), true, c.tls(), repository.terminalPorts(id).sshPort(), null)); }
            catch (ResponseStatusException | IllegalStateException ex) {
                repository.configure(id, new HostRepository.Credentials(c.manufacturer(), c.login(), c.encryptedPassword(), c.port(),
                    true, "CONNECTION_FAILED", "Autenticação inválida. Revise e salve o acesso nas configurações do host.", null, c.tls(), System.currentTimeMillis()));
            }
        }
    }
}
