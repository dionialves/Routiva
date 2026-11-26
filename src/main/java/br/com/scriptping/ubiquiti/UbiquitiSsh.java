package br.com.scriptping.ubiquiti;

import br.com.scriptping.equipment.EquipmentProvider.Connection;
import com.jcraft.jsch.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Bounded read-only exec channels. Unknown host keys are pinned; changed keys are rejected. */
@Component
public class UbiquitiSsh {
    private final JSch client = new JSch();
    public UbiquitiSsh(@Value("${ap.ssh-known-hosts-file:./data/ap-known-hosts}") String filename) throws Exception {
        Path path = Path.of(filename).toAbsolutePath();
        Files.createDirectories(path.getParent());
        if (!Files.exists(path)) {
            try { Files.createFile(path); } catch (FileAlreadyExistsException ignored) { }
        }
        client.setKnownHosts(path.toString());
        var known = client.getHostKeyRepository();
        client.setHostKeyRepository(new HostKeyRepository() {
            public synchronized int check(String host, byte[] key) {
                int result = known.check(host, key);
                if (result == NOT_INCLUDED) {
                    try { known.add(new HostKey(host, key), null); return known.check(host, key); }
                    catch (JSchException ex) { return CHANGED; }
                }
                return result;
            }
            public void add(HostKey key, UserInfo info) { known.add(key, info); }
            public void remove(String host, String type) { known.remove(host, type); }
            public void remove(String host, String type, byte[] key) { known.remove(host, type, key); }
            public String getKnownHostsRepositoryID() { return known.getKnownHostsRepositoryID(); }
            public HostKey[] getHostKey() { return known.getHostKey(); }
            public HostKey[] getHostKey(String host, String type) { return known.getHostKey(host, type); }
        });
    }
    public Link connect(String ip, Connection credentials) throws JSchException {
        return connect(ip, credentials, false);
    }
    public Link connectCisco(String ip, Connection credentials) throws JSchException {
        return connect(ip, credentials, true);
    }
    static void configureCisco(Session session) {
        // IOS XE 16.9 offers only SHA-1 DH; append the verified group after modern choices.
        session.setConfig("kex", session.getConfig("kex") + ",diffie-hellman-group14-sha1");
    }
    private Link connect(String ip, Connection credentials, boolean cisco) throws JSchException {
        var session = client.getSession(credentials.login(), ip, credentials.port());
        session.setPassword(credentials.password());
        session.setConfig("PreferredAuthentications", "password");
        session.setConfig("StrictHostKeyChecking", "yes");
        session.setConfig("server_host_key", session.getConfig("server_host_key") + ",ssh-rsa");
        if (cisco) configureCisco(session);
        session.setTimeout(10000);
        try { session.connect(10000); return new Link(session); }
        catch (JSchException ex) { session.disconnect(); throw ex; }
    }
    public static class Link implements AutoCloseable {
        private final Session session;
        Link(Session session) { this.session = session; }
        /** A bounded interactive CLI, sharing the same pinned SSH host keys. */
        public br.com.scriptping.datacom.DatacomCli datacomCli() throws Exception {
            return new br.com.scriptping.datacom.DatacomCli(session);
        }
        public br.com.scriptping.cisco.CiscoCli ciscoCli() throws Exception {
            return new br.com.scriptping.cisco.CiscoCli(session);
        }
        public void setBackupReadTimeout(int seconds) throws JSchException {
            session.setTimeout(Math.multiplyExact(seconds, 1000));
        }

        public void digistarDump(String password, String destination, String filename, int timeoutSeconds) throws Exception {
            br.com.scriptping.digistar.DigistarCli.dump(session, password, destination, filename, timeoutSeconds);
        }
        public String command(String command) throws Exception {
            if (!java.util.Set.of("mca-status", "wstalist", "cat /proc/net/dev", "ifconfig -a").contains(command))
                throw new IllegalArgumentException("Unsupported SSH command");
            return readCommand(command, 10);
        }
        public String exportRouterOs(int timeoutSeconds) throws Exception {
            return readCommand("/export", timeoutSeconds);
        }
        public String backupAirOs(int timeoutSeconds) throws Exception {
            return readCommand("cat /tmp/system.cfg", timeoutSeconds);
        }
        private String readCommand(String command, int timeoutSeconds) throws Exception {
            var channel = (ChannelExec) session.openChannel("exec");
            try {
                channel.setCommand(command);
                channel.setInputStream(null);
                channel.setErrStream(OutputStream.nullOutputStream());
                var input = channel.getInputStream();
                channel.connect(10000);
                var output = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(timeoutSeconds);
                while (true) {
                    while (input.available() > 0) {
                        int size = input.read(buffer);
                        if (size < 0) break;
                        if (output.size() + size > 4 * 1024 * 1024) throw new IOException("SSH output too large");
                        output.write(buffer, 0, size);
                    }
                    if (channel.isClosed() && input.available() == 0) break;
                    if (System.nanoTime() > deadline) throw new IOException("Tempo limite de " + timeoutSeconds + " s aguardando a resposta do comando SSH.");
                    Thread.sleep(10);
                }
                if (channel.getExitStatus() != 0) throw new IOException("SSH command failed");
                return output.toString(StandardCharsets.UTF_8);
            } finally { channel.disconnect(); }
        }
        public void close() { session.disconnect(); }
    }
}
