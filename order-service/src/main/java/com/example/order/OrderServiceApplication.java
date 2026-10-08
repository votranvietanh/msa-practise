package com.example.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * @SpringBootApplication: gộp 3 annotation (@Configuration, @EnableAutoConfiguration,
 * @ComponentScan) - Spring sẽ tự quét toàn bộ package con của com.example.order để tìm
 * @Component/@Service/@Repository/@RestController/@Configuration và khởi tạo bean.
 *
 * @EnableCaching nằm ở CacheConfig (không phải ở đây) để các test "lát cắt" như
 * @WebMvcTest - vốn chỉ nạp controller + security, không nạp @Configuration - không bị
 * kéo theo cơ chế cache đòi hỏi phải có CacheManager.
 */
@SpringBootApplication
public class OrderServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
