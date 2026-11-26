package br.com.scriptping.backup;

import br.com.scriptping.equipment.EquipmentProvider.Connection;
import br.com.scriptping.ubiquiti.UbiquitiSsh;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class DigistarBackup {
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final int BLOCK = 512;
    private final UbiquitiSsh ssh;
    private final String destination;
    private final String inbox;

    DigistarBackup(UbiquitiSsh ssh, @Value("${BACKUP_TFTP_HOST:}") String destination,
                   @Value("${BACKUP_TFTP_INBOX:}") String inbox) {
        this.ssh = ssh;
        this.destination = destination;
        this.inbox = inbox;
    }

    byte[] capture(String address, Connection credentials, Runnable connected, java.util.function.Consumer<String> progress, int timeoutSeconds) throws Exception {
        if (destination.isBlank() || inbox.isBlank() || !Files.isDirectory(Path.of(inbox))) {
            throw new IOException("Configure o servidor TFTP e a pasta de recebimento da Digistar.");
        }
        String filename = "traceone-" + UUID.randomUUID() + ".tar";
        Path file = Path.of(inbox).resolve(filename);
        try {
            try (var link = ssh.connect(address, credentials)) {
                link.setBackupReadTimeout(timeoutSeconds);
                connected.run();
                link.digistarDump(credentials.password(), destination, filename, timeoutSeconds);
            }
            progress.accept("Aguardando o arquivo da Digistar por TFTP");
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(timeoutSeconds);
            do {
                if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                        byte[] bytes = input.readNBytes(MAX_BYTES + 1);
                        if (bytes.length > MAX_BYTES) throw new IOException("O arquivo Digistar excedeu o limite de 16 MiB.");
                        if (completeTar(bytes)) return bytes;
                    }
                }
                Thread.sleep(250);
            } while (System.nanoTime() < deadline);
            throw new IOException("Arquivo Digistar incompleto ou ausente na pasta TFTP após " + timeoutSeconds + " s.");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    static boolean completeTar(byte[] bytes) {
        if (bytes.length < 3 * BLOCK || bytes.length % BLOCK != 0) return false;
        int offset = 0;
        while (offset + 2 * BLOCK <= bytes.length) {
            if (zeroBlock(bytes, offset)) {
                return offset > 0 && zeroBlock(bytes, offset + BLOCK);
            }
            long checksum = octal(bytes, offset + 148, 8);
            int calculated = 0;
            for (int i = 0; i < BLOCK; i++) {
                calculated += i >= 148 && i < 156 ? 32 : Byte.toUnsignedInt(bytes[offset + i]);
            }
            long size = octal(bytes, offset + 124, 12);
            if (checksum != calculated || size < 0 || size > bytes.length) return false;
            offset += BLOCK + (int) ((size + BLOCK - 1) / BLOCK * BLOCK);
        }
        return false;
    }

    private static boolean zeroBlock(byte[] bytes, int offset) {
        for (int i = offset; i < offset + BLOCK; i++) if (bytes[i] != 0) return false;
        return true;
    }

    private static long octal(byte[] bytes, int offset, int length) {
        String value = new String(bytes, offset, length, StandardCharsets.US_ASCII).trim();
        return value.matches("[0-7]+") ? Long.parseLong(value, 8) : -1;
    }
}
