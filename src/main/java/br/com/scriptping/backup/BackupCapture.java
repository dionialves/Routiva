package br.com.scriptping.backup;

import br.com.scriptping.equipment.EquipmentProvider.Connection;
import br.com.scriptping.hosts.HostConnections;
import br.com.scriptping.hosts.HostRepository;
import br.com.scriptping.mimosa.MimosaHttps;
import br.com.scriptping.ubiquiti.UbiquitiSsh;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

@Component
class BackupCapture {
    private static final Set<String> SUPPORTED = Set.of("MikroTik", "Ubiquiti", "Cisco", "Datacom", "Mimosa", "Digistar");
    private final HostRepository hosts;
    private final HostConnections connections;
    private final UbiquitiSsh ssh;
    private final MimosaHttps mimosa;
    private final DigistarBackup digistar;
    private final int timeoutSeconds;

    record Result(String hostName, String manufacturer, String extension, byte[] content, boolean text) {}

    BackupCapture(HostRepository hosts, HostConnections connections, UbiquitiSsh ssh, MimosaHttps mimosa, DigistarBackup digistar,
                  @org.springframework.beans.factory.annotation.Value("${BACKUP_EXPORT_TIMEOUT_SECONDS:120}") int timeoutSeconds) {
        if (timeoutSeconds < 1 || timeoutSeconds > Integer.MAX_VALUE / 1000) {
            throw new IllegalArgumentException("BACKUP_EXPORT_TIMEOUT_SECONDS fora do intervalo suportado.");
        }
        this.timeoutSeconds = timeoutSeconds;
        this.hosts = hosts;
        this.connections = connections;
        this.ssh = ssh;
        this.mimosa = mimosa;
        this.digistar = digistar;
    }

    static boolean supports(String manufacturer) {
        return manufacturer != null && SUPPORTED.contains(manufacturer);
    }

    Result capture(String hostId, Consumer<String> progress) throws Exception {
        var host = hosts.target(hostId);
        var saved = hosts.credentials(hostId);
        if (saved == null || !supports(saved.manufacturer())) {
            throw new IOException("Configure a autenticação de um fabricante compatível no host.");
        }
        String vendor = saved.manufacturer();
        var credentials = connections.require(host, saved);
        Integer sshPort = vendor.equals("Mimosa") ? null : hosts.terminalPorts(hostId).sshPort();
        try {
            String transport = sshPort == null ? "HTTPS" : "SSH";
            int port = sshPort == null ? credentials.port() : sshPort;
            progress.accept("Conectando ao host via " + transport + " na porta " + port);
            Runnable connected = () -> {
                progress.accept("Conexão realizada com sucesso");
                progress.accept("Iniciando exportação de configurações");
            };
            return capture(host, vendor, credentials, sshPort, connected, progress);
        } catch (com.jcraft.jsch.JSchException ex) {
            if (ex.getMessage() != null && ex.getMessage().startsWith("Auth fail for methods '")) {
                throw authenticationRejected(hostId, saved, sshPort);
            }
            throw new IOException(transportFailure(ex, "SSH", sshPort), ex);
        } catch (MimosaHttps.AuthenticationRejected | br.com.scriptping.digistar.DigistarCli.AuthenticationRejected ex) {
            throw authenticationRejected(hostId, saved, sshPort);
        } catch (javax.net.ssl.SSLException ex) {
            throw new IOException("Falha na negociação TLS ou na validação do certificado HTTPS da Mimosa.", ex);
        } catch (java.net.SocketException | java.net.SocketTimeoutException | java.net.UnknownHostException ex) {
            throw new IOException(transportFailure(ex, sshPort == null ? "HTTPS" : "SSH",
                sshPort == null ? credentials.port() : sshPort), ex);
        }
    }

    private static String transportFailure(Exception error, String protocol, int port) {
        String endpoint = protocol + " na porta " + port;
        if (error instanceof com.jcraft.jsch.JSchAlgoNegoFailException) {
            return "O equipamento e o app não chegaram a um algoritmo SSH em comum.";
        }
        if (error instanceof com.jcraft.jsch.JSchHostKeyException) {
            return "Não foi possível validar a chave de identidade SSH do equipamento.";
        }
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.net.SocketTimeoutException
                || "Connection timed out".equalsIgnoreCase(cause.getMessage())) {
                return "Tempo limite na comunicação " + endpoint + ". Verifique o acesso a partir do servidor TraceOne.";
            }
            if (cause instanceof java.net.ConnectException) {
                return "Falha ao abrir a conexão " + endpoint + ". Confira a porta e se o serviço está acessível.";
            }
            if (cause instanceof java.net.NoRouteToHostException || cause instanceof java.net.UnknownHostException) {
                return "O servidor TraceOne não conseguiu localizar ou alcançar o endereço do host via " + endpoint + ".";
            }
        }
        return "Falha na comunicação " + endpoint + " durante o backup.";
    }

    private IOException authenticationRejected(String hostId, HostRepository.Credentials saved, Integer sshPort) {
        String message = "Credenciais rejeitadas durante o backup. Revise e valide a autenticação do host.";
        hosts.markAuthenticationRejected(hostId, saved, sshPort, message);
        return new IOException(message);
    }

    private Result capture(br.com.scriptping.zabbix.Host host, String vendor, Connection credentials, Integer sshPort,
                           Runnable connected, Consumer<String> progress) throws Exception {
        if (vendor.equals("Mimosa")) {
            byte[] content = mimosa.backup(host.ip(), credentials.port(), credentials.password(), connected, timeoutSeconds);
            String text = textContent(content);
            if (content.length == 0 || text != null && (text.isBlank() ||
                text.stripLeading().matches("(?is)^(?:<!doctype\\s+html|<html|<head|<body|<form).*"))) {
                throw new IOException("A Mimosa não retornou um arquivo de configuração.");
            }
            return new Result(host.name(), vendor, ".conf", content, text != null);
        }

        var terminal = new Connection(credentials.login(), credentials.password(), sshPort);
        if (vendor.equals("Digistar")) {
            return new Result(host.name(), vendor, ".tar", digistar.capture(host.ip(), terminal, connected, progress, timeoutSeconds), false);
        }
        String content = captureSsh(host.ip(), vendor, terminal, connected);
        String normalized = content.toLowerCase(Locale.ROOT);
        boolean valid = switch (vendor) {
            case "MikroTik" -> normalized.contains("routeros") || normalized.contains("/interface") || normalized.contains("/ip address");
            case "Ubiquiti" -> normalized.lines().anyMatch(line -> line.matches("(?:netconf|bridge|radio|wireless)\\..+=.*"));
            case "Cisco" -> normalized.lines().anyMatch(line -> line.stripTrailing().equals("end"));
            case "Datacom" -> !content.isBlank();
            default -> false;
        };
        if (!valid) throw new IOException("O equipamento não retornou uma configuração válida.");
        return new Result(host.name(), vendor, vendor.equals("MikroTik") ? ".rsc" : ".cfg",
            content.getBytes(StandardCharsets.UTF_8), true);
    }

    private String captureSsh(String address, String vendor, Connection credentials, Runnable connected) throws Exception {
        try (var link = vendor.equals("Cisco") ? ssh.connectCisco(address, credentials) : ssh.connect(address, credentials)) {
            link.setBackupReadTimeout(timeoutSeconds);
            connected.run();
            return switch (vendor) {
                case "MikroTik" -> link.exportRouterOs(timeoutSeconds);
                case "Ubiquiti" -> link.backupAirOs(timeoutSeconds);
                case "Cisco" -> {
                    try (var cli = link.ciscoCli()) { yield cli.backupConfiguration(timeoutSeconds); }
                }
                case "Datacom" -> {
                    try (var cli = link.datacomCli()) { yield cli.backupConfiguration(timeoutSeconds); }
                }
                default -> throw new IllegalArgumentException("Fabricante não suportado.");
            };
        }
    }

    static String textContent(byte[] content) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(content)).toString();
            return text.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\t' && c != '\r' && c != '\n')
                ? null : text;
        } catch (CharacterCodingException ex) {
            return null;
        }
    }
}
