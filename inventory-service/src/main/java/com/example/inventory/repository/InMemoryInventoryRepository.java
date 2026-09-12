package com.example.inventory.repository;

import com.example.inventory.entity.StockItem;
import com.example.inventory.event.PaymentSuccessEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory kho hàng, thay cho DB thật.
 *
 * IDEMPOTENCY QUA REDIS: dùng đúng pattern SETNX (setIfAbsent) như
 * payment-service's InMemoryPaymentGateway - xem comment chi tiết ở đó để hiểu vì sao
 * Redis (dùng chung giữa nhiều instance) tốt hơn hẳn 1 Set in-memory (riêng từng instance)
 * khi Inventory Service chạy nhiều instance song song. `stock` bên dưới vẫn là Map
 * in-memory riêng của từng instance - giới hạn này giống hệt "balances" bên Payment Service.
 */
@Repository
public class InMemoryInventoryRepository implements InventoryRepository {

    private static final String RESERVED_KEY_PREFIX = "inventory:reserved:";

    private final Map<String, StockItem> stock = new ConcurrentHashMap<>(Map.of(
            "ITEM-01", new StockItem("ITEM-01", 50),
            "ITEM-02", new StockItem("ITEM-02", 0) // hết hàng sẵn để demo failure path
    ));

    private final StringRedisTemplate redisTemplate;
    private final Duration idempotencyTtl;

    public InMemoryInventoryRepository(StringRedisTemplate redisTemplate,
                                        @Value("${inventory.idempotency-ttl:PT24H}") Duration idempotencyTtl) {
        this.redisTemplate = redisTemplate;
        this.idempotencyTtl = idempotencyTtl;
    }

    /**
     * synchronized: gộp "kiểm tra đủ hàng" + "trừ kho" thành 1 khối ATOMIC TRONG PHẠM VI
     * 1 INSTANCE (nếu bỏ, 2 order khác nhau cùng đặt SKU cuối cùng có thể cùng đọc thấy
     * quantity=1 rồi cả 2 đều trừ thành công -> kho âm). Redis SETNX ở trên giải quyết bài
     * toán KHÁC: chống xử lý trùng CÙNG 1 order giữa NHIỀU instance; 2 cơ chế bổ sung nhau
     * chứ không thay thế nhau.
     */
    @Override
    public synchronized boolean tryReserve(String orderId, List<PaymentSuccessEvent.ItemDTO> items) {
        Boolean firstTime = redisTemplate.opsForValue()
                .setIfAbsent(RESERVED_KEY_PREFIX + orderId, "1", idempotencyTtl);

        if (Boolean.FALSE.equals(firstTime)) {
            return true; // đã xử lý trước đó (idempotent) -> coi như thành công, không trừ lại
        }

        // Check đủ hàng cho TẤT CẢ item trước khi trừ (tránh trừ nửa chừng rồi mới phát hiện thiếu)
        for (PaymentSuccessEvent.ItemDTO item : items) {
            StockItem current = stock.get(item.getSku());
            if (current == null || current.getQuantity() < item.getQty()) {
                // Đã "giữ chỗ" key idempotency ở trên nhưng reserve thực tế lại fail vì hết
                // hàng - xoá key lại để tránh lần thử sau (vd sau khi nhập thêm hàng) bị coi
                // nhầm là "đã reserve rồi" (giống hệt lý do rollback trong PaymentGateway.charge).
                redisTemplate.delete(RESERVED_KEY_PREFIX + orderId);
                return false;
            }
        }

        for (PaymentSuccessEvent.ItemDTO item : items) {
            StockItem current = stock.get(item.getSku());
            current.setQuantity(current.getQuantity() - item.getQty());
        }

        return true;
    }
}
