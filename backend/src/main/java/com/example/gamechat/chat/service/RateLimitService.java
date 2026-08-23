package com.example.gamechat.chat.service;

import com.example.gamechat.common.exception.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

@Service
public class RateLimitService {

    private final StringRedisTemplate redis;
    private final int sendLimit;
    private final int authLimit;
    private final Duration sendWindow;
    private final Duration authWindow;

    public RateLimitService(
            StringRedisTemplate redis,
            @Value("${app.chat.send-rate-limit:20}") int sendLimit,
            @Value("${app.chat.send-rate-window-seconds:10}") int sendWindowSeconds,
            @Value("${app.chat.auth-rate-limit:10}") int authLimit,
            @Value("${app.chat.auth-rate-window-seconds:60}") int authWindowSeconds
    ) {
        this.redis = redis;
        this.sendLimit = sendLimit;
        this.authLimit = authLimit;
        this.sendWindow = Duration.ofSeconds(Math.max(sendWindowSeconds, 1));
        this.authWindow = Duration.ofSeconds(Math.max(authWindowSeconds, 1));
    }

    public void checkSend(UUID userId) {
        hit("ratelimit:send:" + userId, sendLimit, sendWindow, "Sending too quickly");
    }

    public void checkAuth(String clientKey) {
        String key = clientKey == null || clientKey.isBlank() ? "unknown" : clientKey;
        hit("ratelimit:auth:" + key, authLimit, authWindow, "Too many auth attempts");
    }

    private void hit(String key, int limit, Duration window, String message) {
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, window);
        }
        if (count != null && count > limit) {
            throw ApiException.tooManyRequests(message);
        }
    }
}
