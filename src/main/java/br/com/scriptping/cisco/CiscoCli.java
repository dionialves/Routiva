package br.com.scriptping.cisco;

import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.Session;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.regex.Pattern;

/** IOS XE operational CLI only; never enters configuration mode or changes device configuration. */
public class CiscoCli implements AutoCloseable {
    private static final Set<String> COMMANDS = Set.of("terminal length 0", "terminal width 512", "show version", "show interfaces", "show interfaces description");
    static final String PORT = "(?:GigabitEthernet|TenGigabitEthernet|Port-channel|Loopback|Virtual-Access)[0-9]{1,6}(?:/[0-9]{1,6}){0,2}(?:\\.[0-9]{1,6})?";
    private static final Pattern PORT_COMMAND = Pattern.compile("show interfaces " + PORT);
    private final ChannelShell channel;
    private final InputStream input;
    private final OutputStream output;
    private final String prompt;
    private final java.util.function.LongSupplier clock;

    public CiscoCli(Session session) throws Exception { this(session, System::nanoTime); }
    CiscoCli(Session session, java.util.function.LongSupplier clock) throws Exception {
        this.clock = clock;
        channel = (ChannelShell) session.openChannel("shell");
        channel.setPtyType("vt100", 240, 48, 0, 0);
        input = channel.getInputStream(); output = channel.getOutputStream();
        try {
            channel.connect(10000);
            String greeting = receive(null);
            prompt = greeting.substring(greeting.lastIndexOf('\n') + 1).trim();
            command("terminal length 0");
            command("terminal width 512");
        } catch (Exception ex) { channel.disconnect(); throw ex; }
    }
    static boolean allowed(String command) { return COMMANDS.contains(command) || PORT_COMMAND.matcher(command).matches(); }
    public String command(String command) throws Exception {
        if (!allowed(command)) throw new IllegalArgumentException("Unsupported IOS XE command");
        String sent = command;
        output.write((sent + "\n").getBytes(StandardCharsets.UTF_8)); output.flush();
        String result = receive(prompt);
        String body = result.substring(0, result.lastIndexOf(prompt)).trim();
        if (body.startsWith(sent)) body = body.substring(sent.length()).stripLeading();
        if (Pattern.compile("(?im)^\\s*(?:%|syntax error|error:|unknown command|invalid command|permission denied|access denied)").matcher(body).find())
            throw new CommandRejectedException();
        return body;
    }
    static class CommandRejectedException extends IOException {
        CommandRejectedException() { super("IOS XE rejected read-only command"); }
    }
    private String receive(String expected) throws Exception {
        var bytes = new ByteArrayOutputStream();
        long commandDeadline = clock.getAsLong() + 10_000_000_000L;
        while (clock.getAsLong() < commandDeadline) {
            while (input.available() > 0) {
                byte[] buffer = new byte[Math.min(8192, input.available())];
                int n = input.read(buffer);
                if (n < 0) throw new EOFException("SSH CLI closed");
                if (bytes.size() + n > 4 * 1024 * 1024) throw new IOException("SSH output too large");
                bytes.write(buffer, 0, n);
            }
            String text = clean(bytes.toString(StandardCharsets.UTF_8));
            String last = text.substring(text.lastIndexOf('\n') + 1).trim();
            if (expected == null ? last.matches("[\\p{L}\\p{N}_.:@/-]{1,128}[>#]") : last.equals(expected)) return text;
            if (channel.isClosed()) throw new EOFException("SSH CLI closed before prompt");
            Thread.sleep(10);
        }
        throw new IOException("SSH CLI timed out");
    }
    static String clean(String text) { return text.replaceAll("\\x1B\\[[0-?]*[ -/]*[@-~]", "").replace("\r", ""); }
    public void close() { channel.disconnect(); }
}
