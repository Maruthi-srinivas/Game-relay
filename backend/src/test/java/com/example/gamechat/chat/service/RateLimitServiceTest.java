package com.example.gamechat.chat.service;

import com.example.gamechat.common.exception.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RateLimitServiceTest {

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> values;

    private RateLimitService rateLimitService;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(values);
        rateLimitService = new RateLimitService(redis, 2, 10, 3, 60);
    }

    @Test
    void sendAllowsUntilLimitThenRejects() {
        UUID userId = UUID.randomUUID();
        when(values.increment(anyString())).thenReturn(1L, 2L, 3L);

        rateLimitService.checkSend(userId);
        rateLimitService.checkSend(userId);
        assertThatThrownBy(() -> rateLimitService.checkSend(userId))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("RATE_LIMITED");
        verify(redis).expire(anyString(), any());
    }

    @Test
    void authRejectsAfterWindowFills() {
        when(values.increment(anyString())).thenReturn(4L);
        assertThatThrownBy(() -> rateLimitService.checkAuth("127.0.0.1"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("RATE_LIMITED");
        verify(redis, never()).expire(anyString(), any());
    }
}
