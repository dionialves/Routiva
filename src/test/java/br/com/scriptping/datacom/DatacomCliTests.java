package br.com.scriptping.datacom;

import com.jcraft.jsch.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DatacomCliTests {
    static class Input extends InputStream {
        final Queue<Integer> bytes = new ArrayDeque<>();
        void feed(String text) { for (byte b : text.getBytes(StandardCharsets.UTF_8)) bytes.add(b & 255); }
        public int available() { return bytes.size(); }
        public int read() { return bytes.isEmpty() ? -1 : bytes.remove(); }
    }
    @Test void handlesPromptEchoAnsiAndCommandErrorsAndClosesTheShell() throws Exception {
        var session = mock(Session.class); var channel = mock(ChannelShell.class); var input = new Input();
        input.feed("Welcome\r\n\u001b[32mOLT-42#\u001b[0m ");
        when(session.openChannel("shell")).thenReturn(channel); when(channel.getInputStream()).thenReturn(input);
        when(channel.getOutputStream()).thenReturn(new OutputStream() {
            public void write(int b) { }
            public void write(byte[] bytes, int off, int len) {
                String command = new String(bytes, off, len, StandardCharsets.UTF_8).strip();
                input.feed(command + "\r\n" + (command.startsWith("show platform") ? "DM4612" : "syntax error: unknown element") + "\r\nOLT-42# ");
            }
        });
        var clock = new java.util.concurrent.atomic.AtomicLong();
        try (var cli = new DatacomCli(session, clock::get)) {
            assertThat(cli.command("show platform")).isEqualTo("DM4612");
            clock.set(120_000_000_000L); // A persistent shell remains usable after its first minute.
            assertThatThrownBy(() -> cli.command("show firmware")).isInstanceOf(DatacomCli.CommandRejectedException.class);
            assertThat(cli.command("show platform")).isEqualTo("DM4612");
            assertThatThrownBy(() -> cli.command("reboot")).isInstanceOf(IllegalArgumentException.class);
        }
        verify(channel).disconnect();
    }

    @Test
    void waitsForAnOltExportPastTheOrdinaryCommandDeadline() throws Exception {
        var session = mock(Session.class);
        var channel = mock(ChannelShell.class);
        var input = new Input();
        var readsAfterExport = new java.util.concurrent.atomic.AtomicInteger();
        input.feed("OLT-42# ");
        when(session.openChannel("shell")).thenReturn(channel);
        when(channel.getInputStream()).thenReturn(input);
        when(channel.getOutputStream()).thenReturn(new OutputStream() {
            @Override public void write(int value) {}
            @Override public void write(byte[] bytes, int offset, int length) {
                readsAfterExport.set(1);
            }
        });
        try (var cli = new DatacomCli(session, () -> {
            if (readsAfterExport.get() == 0) return 0;
            if (readsAfterExport.getAndIncrement() == 2) {
                input.feed("show running-config | nomore\r\ninterface gpon 1/1/1\r\nOLT-42# ");
                return 30_000_000_000L;
            }
            return 0;
        })) {
            assertThat(cli.backupConfiguration(120)).isEqualTo("interface gpon 1/1/1");
        }
    }
}
