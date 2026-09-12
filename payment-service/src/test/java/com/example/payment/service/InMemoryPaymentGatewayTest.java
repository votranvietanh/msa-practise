package com.example.payment.service;

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

/**
 * Mock StringRedisTemplate thay vì dùng Redis thật - unit test chỉ cần verify LOGIC
 * (khi nào charge, khi nào short-circuit, khi nào rollback key), không cần verify Redis
 * server thật hoạt động đúng (đó là việc của integration test/Testcontainers).
 */
@ExtendWith(MockitoExtension.class)
class InMemoryPaymentGatewayTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private InMemoryPaymentGateway gateway;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        gateway = new InMemoryPaymentGateway(redisTemplate, Duration.ofHours(24));
    }

    @Test
    void charge_thanhCong_khiDuTien() {
        when(valueOperations.setIfAbsent(eq("payment:charged:ORD-1"), eq("1"), any(Duration.class)))
                .thenReturn(true);

        ChargeResult result = gateway.charge("ORD-1", "U001", 250_000L);

        assertThat(result).isEqualTo(ChargeResult.CHARGED);
        assertThat(gateway.getBalance("U001")).isEqualTo(250_000L); // 500_000 - 250_000
    }

    @Test
    void charge_that_bai_khiKhongDuTien_vaXoaKeyIdempotency() {
        when(valueOperations.setIfAbsent(eq("payment:charged:ORD-2"), eq("1"), any(Duration.class)))
                .thenReturn(true);

        ChargeResult result = gateway.charge("ORD-2", "U002", 250_000L); // U002 chỉ có 100_000

        assertThat(result).isEqualTo(ChargeResult.INSUFFICIENT_BALANCE);
        assertThat(gateway.getBalance("U002")).isEqualTo(100_000L); // không bị trừ
        // Charge fail thì phải rollback key idempotency, nếu không lần thử charge sau
        // (redeliver, hoặc user vừa nạp thêm tiền) sẽ bị chặn nhầm.
        verify(redisTemplate).delete("payment:charged:ORD-2");
    }

    @Test
    void charge_idempotent_khiMessageBiRedeliver() {
        // setIfAbsent trả về false = key đã tồn tại = order này đã charge trước đó rồi
        when(valueOperations.setIfAbsent(eq("payment:charged:ORD-3"), eq("1"), any(Duration.class)))
                .thenReturn(false);

        ChargeResult result = gateway.charge("ORD-3", "U001", 250_000L);

        assertThat(result).isEqualTo(ChargeResult.ALREADY_CHARGED);
        assertThat(gateway.getBalance("U001")).isEqualTo(500_000L); // KHÔNG bị trừ lần 2
    }

    @Test
    void refund_thanhCong_conghoanLaiSoDu() {
        when(valueOperations.setIfAbsent(eq("payment:refunded:ORD-4"), eq("1"), any(Duration.class)))
                .thenReturn(true);

        RefundResult result = gateway.refund("ORD-4", "U002", 100_000L);

        assertThat(result).isEqualTo(RefundResult.REFUNDED);
        assertThat(gateway.getBalance("U002")).isEqualTo(200_000L); // 100_000 + 100_000
    }

    @Test
    void refund_idempotent_khiMessageBiRedeliver() {
        when(valueOperations.setIfAbsent(eq("payment:refunded:ORD-5"), eq("1"), any(Duration.class)))
                .thenReturn(false);

        RefundResult result = gateway.refund("ORD-5", "U002", 100_000L);

        assertThat(result).isEqualTo(RefundResult.ALREADY_REFUNDED);
        assertThat(gateway.getBalance("U002")).isEqualTo(100_000L); // KHÔNG hoàn 2 lần
    }
}
