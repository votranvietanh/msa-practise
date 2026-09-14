package com.example.catalog.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

/**
 * Cầu nối giữa Redis Pub/Sub (fire-and-forget, không biết ai đang nghe) và gRPC
 * server-streaming (mỗi client giữ 1 kết nối riêng, server phải chủ động đẩy dữ liệu
 * đúng cho ĐÚNG client đang xem đúng sku). Mỗi lần CatalogGrpcService.watchProduct() được
 * gọi, code subscribe() 1 lần - và BẮT BUỘC phải cancel() khi client ngắt kết nối, nếu
 * không sẽ rò rỉ (leak) 1 MessageListener treo mãi mãi trong container dù không còn ai
 * đọc dữ liệu đó nữa.
 */
@Component
public class RedisProductWatchBridge {

    private final RedisMessageListenerContainer container;
    private final ObjectMapper objectMapper;

    public RedisProductWatchBridge(RedisMessageListenerContainer container, ObjectMapper objectMapper) {
        this.container = container;
        this.objectMapper = objectMapper;
    }

    public Subscription watch(String sku, Consumer<ProductUpdatePayload> onUpdate) {
        ChannelTopic topic = new ChannelTopic(ProductChannels.forSku(sku));

        MessageListener listener = (message, pattern) -> {
            try {
                ProductUpdatePayload payload = objectMapper.readValue(message.getBody(), ProductUpdatePayload.class);
                onUpdate.accept(payload);
            } catch (Exception e) {
                System.err.println("[RedisProductWatchBridge] Không đọc được message cho " + sku + ": " + e.getMessage());
            }
        };

        container.addMessageListener(listener, topic);
        return () -> container.removeMessageListener(listener, topic);
    }

    @FunctionalInterface
    public interface Subscription {
        void cancel();
    }
}
