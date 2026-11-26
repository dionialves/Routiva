package br.com.scriptping.hosts;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Local encryption material is persisted separately from the database; never an equipment credential. */
@Component
public class HostSecrets {
    private final Path path;
    private final SecureRandom random = new SecureRandom();
    public HostSecrets(@Value("${ap.secrets-key-file:./data/ap-secrets.key}") String path) { this.path = Path.of(path); }
    private synchronized byte[] key(boolean create) throws Exception {
        if (!Files.exists(path)) {
            if (!create) throw new IllegalStateException("AP encryption key missing");
            Files.createDirectories(path.toAbsolutePath().getParent());
            byte[] bytes = new byte[32]; random.nextBytes(bytes);
            try {
                Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                Files.write(path, bytes);
            } catch (FileAlreadyExistsException exists) { /* Another process created the key. */ }
        }
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length != 32) throw new IllegalStateException("Invalid AP encryption key");
        return bytes;
    }
    public String encrypt(String value) {
        return encryptBytes(value.getBytes(StandardCharsets.UTF_8));
    }
    public String encryptBytes(byte[] value) {
        try {
            byte[] iv = new byte[12]; random.nextBytes(iv);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key(true), "AES"), new GCMParameterSpec(128, iv));
            return Base64.getEncoder().encodeToString(iv) + ":" + Base64.getEncoder().encodeToString(cipher.doFinal(value));
        } catch (Exception ex) { throw new IllegalStateException("Não foi possível proteger a senha do equipamento."); }
    }
    public String decrypt(String value) {
        return new String(decryptBytes(value), StandardCharsets.UTF_8);
    }
    public byte[] decryptBytes(String value) {
        try {
            var parts = value.split(":", 2);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key(false), "AES"), new GCMParameterSpec(128, Base64.getDecoder().decode(parts[0])));
            return cipher.doFinal(Base64.getDecoder().decode(parts[1]));
        } catch (Exception ex) { throw new IllegalStateException("Não foi possível abrir a senha do equipamento. Verifique o arquivo de proteção."); }
    }
}
