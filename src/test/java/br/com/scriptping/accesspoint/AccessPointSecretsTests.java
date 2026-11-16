package br.com.scriptping.accesspoint;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class AccessPointSecretsTests {
    @TempDir Path temp;
    @Test void encryptedCredentialsSurviveRestartAndUseUniqueNonces() throws Exception {
        String path = temp.resolve("ap.key").toString();
        var secrets = new AccessPointSecrets(path);
        String first = secrets.encrypt("equipment-secret"), second = secrets.encrypt("equipment-secret");
        assertNotEquals(first, second);
        assertFalse(first.contains("equipment-secret"));
        assertEquals("equipment-secret", new AccessPointSecrets(path).decrypt(first));
        assertEquals("equipment-secret", secrets.decrypt(second));
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(Path.of(path))));
    }
    @Test void corruptionOrMissingKeyFailsWithoutCreatingReplacement() throws Exception {
        Path path = temp.resolve("ap.key");
        var secrets = new AccessPointSecrets(path.toString());
        String encrypted = secrets.encrypt("secret");
        assertThrows(IllegalStateException.class, () -> secrets.decrypt(encrypted.substring(0, encrypted.length() - 4) + "AAAA"));
        Files.delete(path);
        assertThrows(IllegalStateException.class, () -> secrets.decrypt(encrypted));
        assertFalse(Files.exists(path));
    }
}
