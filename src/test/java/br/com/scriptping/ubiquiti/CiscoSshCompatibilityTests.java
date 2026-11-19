package br.com.scriptping.ubiquiti;

import com.jcraft.jsch.JSch;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CiscoSshCompatibilityTests {
    @Test void addsLegacyGroupOnlyToCiscoSessionAndPreservesModernPreferenceAndHostVerification() throws Exception {
        var client = new JSch();
        var cisco = client.getSession("reader", "192.0.2.1", 22);
        var other = client.getSession("reader", "192.0.2.2", 22);
        String modern = cisco.getConfig("kex");
        String hostVerification = cisco.getConfig("StrictHostKeyChecking");
        UbiquitiSsh.configureCisco(cisco);
        assertThat(cisco.getConfig("kex")).isEqualTo(modern + ",diffie-hellman-group14-sha1");
        assertThat(other.getConfig("kex")).isEqualTo(modern);
        assertThat(cisco.getConfig("StrictHostKeyChecking")).isEqualTo(hostVerification);
    }
}
