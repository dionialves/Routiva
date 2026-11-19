package br.com.scriptping.mimosa;

import java.nio.file.Path;
import java.security.cert.X509Certificate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MimosaHttpsTests {
    @TempDir Path directory;
    @Test void pinsPerDeviceAndRejectsChangesAcrossRestarts() throws Exception {
        var original = mock(X509Certificate.class);
        var changed = mock(X509Certificate.class);
        when(original.getEncoded()).thenReturn(new byte[]{1, 2, 3});
        when(changed.getEncoded()).thenReturn(new byte[]{4, 5, 6});
        var https = new MimosaHttps(directory.toString());
        https.verify("192.0.2.1:443", original);
        new MimosaHttps(directory.toString()).verify("192.0.2.1:443", original);
        assertThrows(java.security.cert.CertificateException.class,
            () -> new MimosaHttps(directory.toString()).verify("192.0.2.1:443", changed));
        https.verify("192.0.2.2:443", changed);
    }
}
