package com.example.order.service;

import com.example.order.dto.OrderStatusResponse;
import com.example.order.entity.Order;
import com.example.order.entity.OrderStatus;
import com.example.order.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Test này KHÔNG dùng @SpringBootTest, vì @SpringBootTest sẽ load TOÀN BỘ app (bao gồm
 * RabbitConfig/RestClientConfig...) và Spring AMQP sẽ cố kết nối RabbitMQ thật khi context
 * khởi động (RabbitAdmin tự động khai báo exchange/queue/binding lúc app start) - môi trường
 * chạy test không có RabbitMQ nên sẽ fail.
 *
 * Thay vào đó, tự dựng 1 Spring context TỐI GIẢN chỉ đủ để verify hành vi @Cacheable/
 * @CacheEvict của OrderQueryService, dùng ConcurrentMapCacheManager (cache thuần in-memory,
 * có sẵn trong spring-context, không cần Redis) thay cho RedisCacheManager thật. Test này
 * chứng minh CƠ CHẾ CACHE (annotation, key, evict) được wire đúng; nó KHÔNG chứng minh việc
 * serialize JSON sang Redis hoạt động đúng - phần đó cần 1 integration test riêng có Redis
 * thật (vd Testcontainers), nằm ngoài phạm vi bộ unit test này.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = OrderQueryServiceCacheTest.TestConfig.class)
class OrderQueryServiceCacheTest {

    @Configuration
    @EnableCaching
    static class TestConfig {
        @Bean
        ConcurrentMapCacheManager cacheManager() {
            return new ConcurrentMapCacheManager("orderStatus", "orderOwner");
        }

        @Bean
        OrderRepository orderRepository() {
            return mock(OrderRepository.class);
        }

        @Bean
        OrderQueryService orderQueryService(OrderRepository orderRepository) {
            return new OrderQueryService(orderRepository);
        }
    }

    @Autowired
    private OrderQueryService orderQueryService;

    @Autowired
    private OrderRepository orderRepository;

    @Test
    void getStatus_lanGoiThu2_layTuCache_khongDocLaiRepository() {
        Order order = new Order("ORD-1", "U001", 250_000L, List.of(), OrderStatus.PENDING);
        when(orderRepository.findById("ORD-1")).thenReturn(Optional.of(order));

        OrderStatusResponse first = orderQueryService.getStatus("ORD-1");
        OrderStatusResponse second = orderQueryService.getStatus("ORD-1");

        assertThat(first.getStatus()).isEqualTo("PENDING");
        assertThat(second.getStatus()).isEqualTo("PENDING");
        verify(orderRepository, times(1)).findById("ORD-1"); // lần gọi thứ 2 lấy từ cache
    }

    @Test
    void evictStatusCache_thiLanGoiSauDoDocLaiRepository() {
        Order order = new Order("ORD-2", "U001", 250_000L, List.of(), OrderStatus.PENDING);
        when(orderRepository.findById("ORD-2")).thenReturn(Optional.of(order));

        orderQueryService.getStatus("ORD-2");           // cache miss lần 1 -> gọi repository
        orderQueryService.evictStatusCache("ORD-2");     // xoá cache (giả lập OrderStatusListener gọi khi status đổi)
        orderQueryService.getStatus("ORD-2");             // phải cache miss lại -> gọi repository lần nữa

        verify(orderRepository, times(2)).findById("ORD-2");
    }

    @Test
    void getOwnerId_traVeChuDon_vaDuocCache() {
        Order order = new Order("ORD-3", "U001", 250_000L, List.of(), OrderStatus.PENDING);
        when(orderRepository.findById("ORD-3")).thenReturn(Optional.of(order));

        assertThat(orderQueryService.getOwnerId("ORD-3")).isEqualTo("U001");
        assertThat(orderQueryService.getOwnerId("ORD-3")).isEqualTo("U001");

        verify(orderRepository, times(1)).findById("ORD-3");
    }

    @Test
    void getOwnerId_orderKhongTonTai_traVeNull() {
        when(orderRepository.findById("ORD-MA")).thenReturn(Optional.empty());

        assertThat(orderQueryService.getOwnerId("ORD-MA")).isNull();
    }

    @Test
    void getStatus_orderKhongTonTai_khongCache_ketQuaNull() {
        when(orderRepository.findById("ORD-KHONG-TON-TAI")).thenReturn(Optional.empty());

        OrderStatusResponse first = orderQueryService.getStatus("ORD-KHONG-TON-TAI");
        OrderStatusResponse second = orderQueryService.getStatus("ORD-KHONG-TON-TAI");

        assertThat(first).isNull();
        assertThat(second).isNull();
        // unless = "#result == null" -> KHÔNG cache kết quả null, nên cả 2 lần đều phải
        // đọc lại repository (tránh cache nhầm "not found" nếu order được tạo ngay sau đó).
        verify(orderRepository, times(2)).findById("ORD-KHONG-TON-TAI");
    }
}
