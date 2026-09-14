package com.example.catalog.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisProductEventPublisher implements ProductEventPublisher {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisProductEventPublisher(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * PUBLISH: gửi 1 message vào kênh, "bắn rồi quên" (fire-and-forget) - KHÁC HẲN
     * RabbitMQ mà bản order-saga-demo dùng: nếu không có subscriber nào đang lắng nghe
     * kênh này tại đúng thời điểm PUBLISH, message BIẾN MẤT NGAY LẬP TỨC, không hề được
     * lưu lại ở đâu cả (RabbitMQ queue còn giữ message cho tới khi có consumer xử lý).
     * Vì vậy Redis Pub/Sub CHỈ phù hợp cho dữ liệu "không sao nếu miss" (thông báo real-time
     * kiểu best-effort) - không dùng được cho việc gì cần đảm bảo chắc chắn tới nơi (như
     * charge tiền, trừ kho) - những việc đó vẫn phải qua message queue thật hoặc DB transaction.
     */
    @Override
    public void publish(String sku, String type, long newQuantity, String message) {
        try {
            String json = objectMapper.writeValueAsString(
                    new ProductUpdatePayload(sku, type, newQuantity, message));
            redisTemplate.convertAndSend(ProductChannels.forSku(sku), json);
        } catch (Exception e) {
            // Lỗi serialize JSON không nên làm fail luồng nghiệp vụ chính (vd BulkRestock) -
            // đây chỉ là thông báo phụ, không phải nguồn sự thật (Redis vẫn giữ đúng tồn kho
            // dù publish lỗi).
            System.err.println("[ProductEventPublisher] Không publish được update cho " + sku + ": " + e.getMessage());
        }
    }
}
