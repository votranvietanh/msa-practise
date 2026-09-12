package com.example.order.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import java.time.Duration;

/**
 * Tuỳ biến RedisCacheManager mà Spring Boot tự tạo (do có spring-boot-starter-data-redis
 * trên classpath + spring.cache.type=redis trong application.yml).
 */
@Configuration
public class CacheConfig {

    /**
     * RedisCacheManagerBuilderCustomizer: Spring Boot tự động gọi customizer này khi build
     * RedisCacheManager, thay vì phải tự tay khai báo cả 1 CacheManager từ đầu.
     *
     * - entryTtl(5s): TTL ngắn vì dữ liệu này được Frontend polling liên tục và cần khá
     *   "tươi" (status đổi phải thấy sớm) - khác với cache kiểu "danh mục sản phẩm" thường
     *   để TTL hàng giờ. Chọn TTL bao lâu luôn là đánh đổi giữa độ tươi dữ liệu và tải lên
     *   nguồn gốc, 5s là hợp lý cho 1 endpoint polling.
     * - GenericJackson2JsonRedisSerializer(objectMapper): lưu giá trị cache dưới dạng JSON
     *   (đọc được bằng mắt qua `redis-cli GET`) thay vì mặc định của Spring Boot là Java
     *   serialization nhị phân (JdkSerializationRedisSerializer) - vừa dễ debug, vừa không
     *   bắt buộc DTO phải implements Serializable. Dùng lại `objectMapper` bean có sẵn của
     *   Spring (đã tự đăng ký JavaTimeModule để serialize java.time.Instant) thay vì tạo
     *   ObjectMapper mới, để hành vi serialize JSON nhất quán với phần còn lại của app.
     */
    @Bean
    public RedisCacheManagerBuilderCustomizer redisCacheManagerBuilderCustomizer(ObjectMapper objectMapper) {
        GenericJackson2JsonRedisSerializer serializer = new GenericJackson2JsonRedisSerializer(objectMapper);

        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofSeconds(5))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(serializer));

        return builder -> builder.cacheDefaults(config);
    }
}
