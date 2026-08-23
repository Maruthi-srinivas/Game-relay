package com.example.gamechat.auth.security;

import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private final JwtService jwtService = new JwtService("test-secret-must-be-at-least-32-chars-long", 3_600_000);

    @Test
    void roundTripPreservesIdentity() {
        UUID userId = UUID.randomUUID();
        String token = jwtService.createToken(userId, "alice");
        UserPrincipal principal = jwtService.parse(token);
        assertThat(principal.userId()).isEqualTo(userId);
        assertThat(principal.username()).isEqualTo("alice");
        assertThat(principal.jti()).isNotBlank();
    }

    @Test
    void rejectsTamperedToken() {
        String token = jwtService.createToken(UUID.randomUUID(), "alice");
        assertThatThrownBy(() -> jwtService.parse(token + "x"))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsExpiredToken() throws Exception {
        JwtService shortLived = new JwtService("test-secret-must-be-at-least-32-chars-long", 1);
        String token = shortLived.createToken(UUID.randomUUID(), "alice");
        Thread.sleep(20);
        assertThatThrownBy(() -> shortLived.parse(token))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsShortSecret() {
        assertThatThrownBy(() -> new JwtService("too-short", 1000))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
