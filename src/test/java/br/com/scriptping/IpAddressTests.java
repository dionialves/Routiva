package br.com.scriptping;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IpAddressTests {

    @Test
    void acceptsNumericIpv4AndIpv6Addresses() {
        for (String ip : new String[] {
            "127.0.0.1",
            "192.168.1.254",
            "::1",
            "2001:db8::1",
            "::ffff:192.0.2.1",
        }) {
            assertThat(IpAddress.isValid(ip)).as(ip).isTrue();
        }
    }

    @Test
    void rejectsMissingMalformedAndUnspecifiedTargetsWithoutDns() {
        for (String ip : new String[] {
            null,
            "",
            " ",
            "999.1.1.1",
            "1.2.3",
            "1.2.3.4.5",
            "deadbeef",
            "localhost",
            "abc::xyz",
            "::::",
            "0.0.0.0",
            "::",
            "127.0.0.1;ls",
        }) {
            assertThat(IpAddress.isValid(ip)).as(String.valueOf(ip)).isFalse();
            assertThat(new PingProbe().ping(ip, 100).error()).isEqualTo("IP ausente ou inválido");
        }
    }
}
