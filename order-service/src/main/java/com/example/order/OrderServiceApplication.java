package com.example.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

/**
 * @SpringBootApplication: gộp 3 annotation (@Configuration, @EnableAutoConfiguration,
 * @ComponentScan) - Spring sẽ tự quét toàn bộ package con của com.example.order để tìm
 * @Component/@Service/@Repository/@RestController/@Configuration và khởi tạo bean.
 *
 * @EnableCaching: bật cơ chế cache abstraction của Spring (@Cacheable/@CacheEvict) dùng
 * trong InMemoryOrderRepository — nếu thiếu annotation này, các annotation cache sẽ bị
 * Spring bỏ qua hoàn toàn (không lỗi, chỉ đơn giản là không cache gì cả).
 */
@SpringBootApplication
@EnableCaching
public class OrderServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
