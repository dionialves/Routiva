package br.com.scriptping.security;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class LoginThrottleTests {
    @Test void limitsRepeatedUserAttemptsAcrossAddresses() {
        var throttle = new LoginThrottle();
        for (int i = 0; i < 15; i++) throttle.check("address" + i, "same-user");
        assertThatThrownBy(() -> throttle.check("another-address", "same-user"))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class).hasMessageContaining("429");
        assertThatCode(() -> throttle.check("another-address", "another-user")).doesNotThrowAnyException();
    }
    @Test void limitsAddressAttemptsAcrossUsernames() {
        var throttle = new LoginThrottle();
        for (int i = 0; i < 60; i++) throttle.check("same-address", "user" + i);
        assertThatThrownBy(() -> throttle.check("same-address", "another-user"))
            .hasMessageContaining("429");
    }
}
