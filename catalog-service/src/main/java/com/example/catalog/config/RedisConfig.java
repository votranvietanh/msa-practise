package com.example.catalog.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
public class RedisConfig {

    /**
     * RedisMessageListenerContainer: giữ 1 kết nối Redis riêng LUÔN MỞ để lắng nghe
     * Pub/Sub (khác kết nối StringRedisTemplate dùng cho lệnh thông thường như GET/SET) -
     * đây là lý do Pub/Sub cần 1 connection riêng: 1 khi kết nối đã SUBSCRIBE, nó không thể
     * dùng để chạy lệnh Redis khác nữa cho tới khi UNSUBSCRIBE.
     *
     * Đây là 1 Spring bean bình thường (không tự new() thủ công) vì
     * RedisMessageListenerContainer implement sẵn InitializingBean/DisposableBean/
     * SmartLifecycle - Spring sẽ tự start() lúc app khởi động và stop() lúc app tắt,
     * không cần code thêm gì.
     */
    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        return container;
    }
}
