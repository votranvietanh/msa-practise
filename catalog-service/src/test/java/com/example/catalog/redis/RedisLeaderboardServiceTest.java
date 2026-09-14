package com.example.catalog.redis;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RedisLeaderboardServiceTest {

    private static final String KEY = "catalog:leaderboard:bestseller";

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ZSetOperations<String, String> zSetOperations;

    private RedisLeaderboardService leaderboardService;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        leaderboardService = new RedisLeaderboardService(redisTemplate);
    }

    @Test
    void recordSale_dungIncrementScore() {
        leaderboardService.recordSale("SKU-1", 5);

        verify(zSetOperations).incrementScore(KEY, "SKU-1", 5.0);
    }

    @Test
    void topSellers_traVeDungThuTuVaGiaTri() {
        Set<TypedTuple<String>> tuples = new LinkedHashSet<>();
        tuples.add(TypedTuple.of("SKU-HOT", 50.0));
        tuples.add(TypedTuple.of("SKU-WARM", 20.0));
        when(zSetOperations.reverseRangeWithScores(KEY, 0, 1)).thenReturn(tuples);

        List<TopSellerEntry> result = leaderboardService.topSellers(2);

        assertThat(result).containsExactly(
                new TopSellerEntry("SKU-HOT", 50L),
                new TopSellerEntry("SKU-WARM", 20L));
    }

    @Test
    void topSellers_traVeRong_khiChuaCoDuLieu() {
        when(zSetOperations.reverseRangeWithScores(anyString(), anyLong(), anyLong())).thenReturn(null);

        assertThat(leaderboardService.topSellers(5)).isEmpty();
    }
}
