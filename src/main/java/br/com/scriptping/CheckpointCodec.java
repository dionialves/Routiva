package br.com.scriptping;

import java.io.*;
import java.security.*;
import java.util.HexFormat;
import java.util.zip.*;

/** Explicit versioned payloads, never Java object serialization. */
final class CheckpointCodec {
    interface Writer { void write(DataOutputStream out) throws IOException; }
    interface Reader { void read(DataInputStream in) throws IOException; }
    static byte[] encode(Writer writer) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(new GZIPOutputStream(bytes))) { out.writeInt(1); writer.write(out); }
            return bytes.toByteArray();
        } catch (IOException e) { throw new IllegalStateException(e); }
    }
    static void decode(byte[] bytes, Reader reader) {
        try (var in = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(bytes)))) {
            if (in.readInt() != 1) throw new IOException("Unsupported checkpoint format");
            reader.read(in);
            if (in.read() != -1) throw new IOException("Trailing checkpoint data");
        } catch (IOException e) { throw new IllegalArgumentException("Invalid checkpoint payload", e); }
    }
    static int count(DataInputStream in, int max) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > max) throw new IOException("Invalid checkpoint count");
        return n;
    }
    static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    static void sample(DataOutputStream out, PingSample s) throws IOException {
        out.writeLong(s.id()); out.writeUTF(s.hostId()); out.writeUTF(s.target()); out.writeLong(s.measuredAt());
        out.writeBoolean(s.latency() != null); if (s.latency() != null) out.writeDouble(s.latency());
        out.writeBoolean(s.error() != null); if (s.error() != null) out.writeUTF(s.error());
        out.writeDouble(s.latencyLimit());
    }
    static PingSample sample(DataInputStream in) throws IOException {
        return new PingSample(in.readLong(), in.readUTF(), in.readUTF(), in.readLong(),
            in.readBoolean() ? in.readDouble() : null, in.readBoolean() ? in.readUTF() : null, in.readDouble());
    }
}
