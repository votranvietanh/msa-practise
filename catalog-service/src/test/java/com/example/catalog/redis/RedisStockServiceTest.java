package com.example.catalog.redis;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RedisStockServiceTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;

    private RedisStockService stockService;

    @BeforeEach
    void setUp() {
        stockService = new RedisStockService(redisTemplate);
    }

    @Test
    void initStock_dungSetIfAbsent_khongGhiDeTonKhoDaCo() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        stockService.initStock("SKU-1", 100);

        verify(valueOperations).setIfAbsent("catalog:stock:SKU-1", "100");
    }

    @Test
    void getStock_traVe0_khiChuaTungInit() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("catalog:stock:SKU-2")).thenReturn(null);

        assertThat(stockService.getStock("SKU-2")).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void tryReserve_goiLuaScript_dungKeyVaSoLuong_traVeKetQuaScript() {
        when(redisTemplate.execute(any(RedisScript.class), eq(List.of("catalog:stock:SKU-3")), eq("10")))
                .thenReturn(40L);

        long remaining = stockService.tryReserve("SKU-3", 10);

        assertThat(remaining).isEqualTo(40L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void tryReserve_traVeAmMot_khiScriptBaoHetHang() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any())).thenReturn(-1L);

        assertThat(stockService.tryReserve("SKU-4", 999)).isEqualTo(-1L);
    }

    @Test
    void restock_dungIncrement_traVeTonKhoMoi() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment("catalog:stock:SKU-5", 20L)).thenReturn(120L);

        assertThat(stockService.restock("SKU-5", 20)).isEqualTo(120L);
    }
}
