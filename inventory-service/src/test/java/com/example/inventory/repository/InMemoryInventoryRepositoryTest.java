package com.example.inventory.repository;

import com.example.inventory.event.PaymentSuccessEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InMemoryInventoryRepositoryTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private InMemoryInventoryRepository repository;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        repository = new InMemoryInventoryRepository(redisTemplate, Duration.ofHours(24));
    }

    private PaymentSuccessEvent.ItemDTO item(String sku, int qty) {
        PaymentSuccessEvent.ItemDTO dto = new PaymentSuccessEvent.ItemDTO();
        dto.setSku(sku);
        dto.setQty(qty);
        return dto;
    }

    @Test
    void tryReserve_thanhCong_khiConDuHang() {
        when(valueOperations.setIfAbsent(eq("inventory:reserved:ORD-1"), eq("1"), any(Duration.class)))
                .thenReturn(true);

        boolean reserved = repository.tryReserve("ORD-1", List.of(item("ITEM-01", 2)));

        assertThat(reserved).isTrue();
    }

    @Test
    void tryReserve_thatBai_khiHetHang_vaRollbackKeyIdempotency() {
        when(valueOperations.setIfAbsent(eq("inventory:reserved:ORD-2"), eq("1"), any(Duration.class)))
                .thenReturn(true);

        // ITEM-02 mặc định = 0 tồn kho (xem InMemoryInventoryRepository)
        boolean reserved = repository.tryReserve("ORD-2", List.of(item("ITEM-02", 1)));

        assertThat(reserved).isFalse();
        // Reserve fail thì phải xoá key idempotency, không thì lần thử sau (vd sau khi
        // nhập thêm hàng) sẽ bị chặn nhầm là "đã reserve rồi".
        verify(redisTemplate).delete("inventory:reserved:ORD-2");
    }

    @Test
    void tryReserve_idempotent_khiMessageBiRedeliver() {
        // setIfAbsent = false -> order này đã reserve thành công trước đó rồi
        when(valueOperations.setIfAbsent(eq("inventory:reserved:ORD-3"), eq("1"), any(Duration.class)))
                .thenReturn(false);

        boolean reserved = repository.tryReserve("ORD-3", List.of(item("ITEM-02", 999))); // dù item không đủ hàng

        // Vẫn trả về true vì coi như đã xử lý xong từ trước - KHÔNG kiểm tra lại tồn kho
        assertThat(reserved).isTrue();
    }

    @Test
    void tryReserve_truDuKhoChoNhieuItem_khiDuHangHetTatCa() {
        when(valueOperations.setIfAbsent(eq("inventory:reserved:ORD-4"), eq("1"), any(Duration.class)))
                .thenReturn(true);

        boolean reserved = repository.tryReserve("ORD-4",
                List.of(item("ITEM-01", 10), item("ITEM-02", 1))); // ITEM-02 hết hàng

        // 1 trong 2 item hết hàng -> KHÔNG được trừ item còn lại (all-or-nothing)
        assertThat(reserved).isFalse();
    }
}
