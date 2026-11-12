package br.com.scriptping.mikrotik;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.net.ssl.*;

/** One bounded API connection per HTTP request; no background collection or persistence. */
final class RouterOsConnection implements AutoCloseable {
    private final Socket socket;
    private final InputStream input;
    private final OutputStream output;
    private final long deadline = System.nanoTime() + 15_000_000_000L;

    RouterOsConnection(String host, int port, boolean tls, String username, String password) throws IOException {
        socket = tls ? SSLSocketFactory.getDefault().createSocket() : new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), 3000);
            socket.setSoTimeout(4000);
            if (socket instanceof SSLSocket ssl) {
                var parameters = ssl.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                ssl.setSSLParameters(parameters);
                ssl.startHandshake();
            }
            input = socket.getInputStream();
            output = socket.getOutputStream();
            command("/login", "=name=" + username, "=password=" + password);
        } catch (IOException | RuntimeException ex) {
            socket.close();
            throw ex;
        }
    }

    List<Map<String, String>> command(String... words) throws IOException {
        for (String word : words) {
            byte[] bytes = word.getBytes(StandardCharsets.UTF_8);
            writeLength(output, bytes.length);
            output.write(bytes);
        }
        output.write(0);
        output.flush();
        var rows = new ArrayList<Map<String, String>>();
        boolean trapped = false;
        int totalBytes = 0;
        for (int sentences = 0; sentences < 20000; sentences++) {
            var sentence = new ArrayList<String>();
            while (true) {
                if (System.nanoTime() > deadline) throw new IOException("API deadline exceeded");
                int size = readLength(input);
                if (size == 0) break;
                totalBytes += size;
                if (size > 1_048_576 || totalBytes > 8_388_608) throw new IOException("API response too large");
                byte[] bytes = input.readNBytes(size);
                if (bytes.length != size) throw new EOFException();
                sentence.add(new String(bytes, StandardCharsets.UTF_8));
            }
            if (sentence.isEmpty()) continue;
            var row = new LinkedHashMap<String, String>();
            for (String word : sentence) {
                if (!word.startsWith("=")) continue;
                int split = word.indexOf('=', 1);
                if (split > 1) row.put(word.substring(1, split), word.substring(split + 1));
            }
            switch (sentence.getFirst()) {
                case "!re" -> rows.add(row);
                case "!trap" -> trapped = true;
                case "!fatal" -> throw new IOException("API connection closed");
                case "!done" -> {
                    if (trapped) throw new CommandRejectedException();
                    return rows;
                }
                default -> { }
            }
        }
        throw new IOException("Too many API sentences");
    }

    static class CommandRejectedException extends IOException { }

    static void writeLength(OutputStream out, int length) throws IOException {
        if (length < 0x80) out.write(length);
        else if (length < 0x4000) { out.write((length >> 8) | 0x80); out.write(length); }
        else if (length < 0x200000) { out.write((length >> 16) | 0xc0); out.write(length >> 8); out.write(length); }
        else if (length < 0x10000000) {
            out.write((length >> 24) | 0xe0); out.write(length >> 16); out.write(length >> 8); out.write(length);
        } else { out.write(0xf0); out.write(length >> 24); out.write(length >> 16); out.write(length >> 8); out.write(length); }
    }

    static int readLength(InputStream in) throws IOException {
        int first = readByte(in);
        if (first < 0x80) return first;
        int count;
        long length;
        if (first < 0xc0) { count = 1; length = first & 0x3f; }
        else if (first < 0xe0) { count = 2; length = first & 0x1f; }
        else if (first < 0xf0) { count = 3; length = first & 0x0f; }
        else if (first == 0xf0) { count = 4; length = 0; }
        else throw new IOException("Unsupported API length");
        for (int i = 0; i < count; i++) length = (length << 8) | readByte(in);
        if (length > Integer.MAX_VALUE) throw new IOException("API word too large");
        return (int) length;
    }

    private static int readByte(InputStream in) throws IOException {
        int value = in.read();
        if (value < 0) throw new EOFException();
        return value;
    }

    @Override public void close() throws IOException { socket.close(); }
}
