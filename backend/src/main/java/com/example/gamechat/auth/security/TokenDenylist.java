package com.example.gamechat.auth.security;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

@Service
public class TokenDenylist {

    private final StringRedisTemplate redis;

    public TokenDenylist(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void deny(String jti, Instant expiresAt) {
        if (jti == null || jti.isBlank()) {
            return;
        }
        long ttl = Math.max(1, Duration.between(Instant.now(), expiresAt).toSeconds());
        redis.opsForValue().set(key(jti), "1", Duration.ofSeconds(ttl));
    }

    public boolean isDenied(String jti) {
        if (jti == null || jti.isBlank()) {
            return false;
        }
        return Boolean.TRUE.equals(redis.hasKey(key(jti)));
    }

    private static String key(String jti) {
        return "auth:deny:" + jti;
    }
}
