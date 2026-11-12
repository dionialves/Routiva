package br.com.scriptping.security;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class LoginThrottle {
    private record Attempts(long expiresAt, int count) {}
    private final Map<String, Attempts> attempts = new HashMap<>();
    public synchronized void check(String address, String username) {
        long now = System.currentTimeMillis();
        attempts.entrySet().removeIf(entry -> entry.getValue().expiresAt < now);
        if (attempts.size() >= 10000) throw blocked();
        hit("ip:" + address, 60, now);
        hit("user:" + username, 15, now);
    }
    private void hit(String key, int maximum, long now) {
        var value = attempts.getOrDefault(key, new Attempts(now + 15 * 60 * 1000, 0));
        if (value.count >= maximum) throw blocked();
        attempts.put(key, new Attempts(value.expiresAt, value.count + 1));
    }
    private ResponseStatusException blocked() {
        return new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Muitas tentativas. Aguarde 15 minutos e tente novamente.");
    }
}
