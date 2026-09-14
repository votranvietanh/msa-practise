package com.example.catalog.redis;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RedisRateLimiterTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;

    private RedisRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        rateLimiter = new RedisRateLimiter(redisTemplate);
    }

    @Test
    void choPhep_khiConDuoiLimit() {
        when(valueOperations.increment("catalog:ratelimit:foo")).thenReturn(3L);

        boolean allowed = rateLimiter.tryAcquire("foo", 5, Duration.ofSeconds(10));

        assertThat(allowed).isTrue();
        verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    void tuChoi_khiVuotLimit() {
        when(valueOperations.increment("catalog:ratelimit:foo")).thenReturn(6L);

        boolean allowed = rateLimiter.tryAcquire("foo", 5, Duration.ofSeconds(10));

        assertThat(allowed).isFalse();
    }

    @Test
    void chiSetTTL_oLanGoiDauTien_khiCounterVuaTao() {
        when(valueOperations.increment("catalog:ratelimit:bar")).thenReturn(1L);

        rateLimiter.tryAcquire("bar", 5, Duration.ofSeconds(10));

        verify(redisTemplate).expire("catalog:ratelimit:bar", Duration.ofSeconds(10));
    }

    @Test
    void khongSetLaiTTL_oNhungLanGoiSau() {
        when(valueOperations.increment("catalog:ratelimit:baz")).thenReturn(2L);

        rateLimiter.tryAcquire("baz", 5, Duration.ofSeconds(10));

        verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
    }
}
