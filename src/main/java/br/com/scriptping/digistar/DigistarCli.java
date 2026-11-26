package br.com.scriptping.digistar;

import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.Session;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/** The Digistar dump requires the privileged operational prompt and a TFTP destination. */
public final class DigistarCli {
    private static final Pattern USER = Pattern.compile("(?s).*[^\\s]>\\s*$");
    private static final Pattern PRIVILEGED = Pattern.compile("(?s).*[^\\s]#\\s*$");
    private static final Pattern PASSWORD = Pattern.compile("(?s).*Enter the programming password:?\\s*$");

    public static final class AuthenticationRejected extends IOException {
        AuthenticationRejected() { super("Senha de programação rejeitada pela Digistar."); }
    }

    private DigistarCli() {}

    public static void dump(Session session, String password, String destination, String filename, int timeoutSeconds) throws Exception {
        if (!destination.matches("[a-zA-Z0-9][a-zA-Z0-9.-]{0,252}") ||
            !filename.matches("traceone-[0-9a-f-]{36}\\.tar") || password.matches("(?s).*[\\r\\n\\p{Cntrl}].*")) {
            throw new IllegalArgumentException("Parâmetros inválidos para a sessão Digistar.");
        }
        var channel = (ChannelShell) session.openChannel("shell");
        try {
            var input = channel.getInputStream();
            var output = channel.getOutputStream();
            channel.connect(5000);
            receive(channel, input, USER, 5);
            send(output, "enable");
            receive(channel, input, PASSWORD, 5);
            send(output, password);
            receive(channel, input, PRIVILEGED, 5);
            send(output, "terminal length 0");
            receive(channel, input, PRIVILEGED, 5);
            send(output, "dump network " + destination + " " + filename);
            receive(channel, input, PRIVILEGED, timeoutSeconds);
        } finally {
            channel.disconnect();
        }
    }

    private static void send(java.io.OutputStream output, String value) throws IOException {
        output.write((value + "\n").getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private static void receive(ChannelShell channel, java.io.InputStream input, Pattern prompt, int seconds) throws Exception {
        var result = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        long deadline = System.nanoTime() + seconds * 1_000_000_000L;
        while (System.nanoTime() < deadline) {
            while (input.available() > 0) {
                int count = input.read(buffer, 0, Math.min(buffer.length, input.available()));
                if (count < 0) throw new EOFException("Sessão Digistar encerrada.");
                if (result.size() + count > 64 * 1024) throw new IOException("Resposta Digistar excedeu o limite.");
                result.write(buffer, 0, count);
            }
            String response = result.toString(StandardCharsets.UTF_8);
            if (prompt.matcher(response).matches()) return;
            if (prompt == PRIVILEGED && PASSWORD.matcher(response).matches()) throw new AuthenticationRejected();
            if (channel.isClosed()) throw new EOFException("Sessão Digistar encerrada.");
            Thread.sleep(25);
        }
        throw new IOException("A Digistar não retornou o prompt esperado em " + seconds + " s.");
    }
}
