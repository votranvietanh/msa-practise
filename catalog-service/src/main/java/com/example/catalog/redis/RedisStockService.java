package com.example.catalog.redis;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RedisStockService implements StockService {

    private static final String KEY_PREFIX = "catalog:stock:";

    /**
     * Lua script chạy TRÊN SERVER REDIS (không phải trên app Java) - đây là lý do nó
     * atomic: Redis xử lý từng lệnh/script tuần tự, KHÔNG có request nào khác chen ngang
     * giữa lúc script đang chạy, dù script gồm nhiều bước (đọc rồi ghi). Nếu tách "đọc tồn
     * kho" và "trừ tồn kho" thành 2 lệnh Redis riêng gọi từ Java (GET rồi DECRBY), 2 request
     * gRPC ReserveStockSession đến gần như đồng thời có thể CÙNG đọc thấy tồn kho còn đủ
     * hàng trước khi bên nào kịp trừ -> bán vượt tồn kho (race condition kinh điển, giống
     * hệt lý do InventoryRepository.tryReserve() bên order-saga-demo phải dùng `synchronized`
     * - khác biệt là ở ĐÓ synchronized chỉ đúng trong 1 instance, còn Lua script ở ĐÂY đúng
     * trên TOÀN CỤM Redis dù app có chạy bao nhiêu instance song song).
     *
     * KEYS[1] = key tồn kho, ARGV[1] = số lượng muốn trừ.
     * Trả về -1 nếu không đủ hàng (không trừ gì), ngược lại trả về tồn kho còn lại.
     */
    private static final RedisScript<Long> RESERVE_SCRIPT = new DefaultRedisScript<>("""
            local current = tonumber(redis.call('GET', KEYS[1]) or '0')
            local qty = tonumber(ARGV[1])
            if current < qty then
              return -1
            end
            redis.call('DECRBY', KEYS[1], qty)
            return current - qty
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    public RedisStockService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void initStock(String sku, long quantity) {
        // setIfAbsent thay vì set: tạo sản phẩm 2 lần cho cùng 1 sku (vd do client gọi lại)
        // không được phép "reset" tồn kho hiện tại về giá trị ban đầu nữa.
        redisTemplate.opsForValue().setIfAbsent(key(sku), String.valueOf(quantity));
    }

    @Override
    public long getStock(String sku) {
        String value = redisTemplate.opsForValue().get(key(sku));
        return value == null ? 0L : Long.parseLong(value);
    }

    @Override
    public long tryReserve(String sku, long quantity) {
        Long result = redisTemplate.execute(RESERVE_SCRIPT, List.of(key(sku)), String.valueOf(quantity));
        return result == null ? -1L : result;
    }

    @Override
    public long restock(String sku, long quantity) {
        Long result = redisTemplate.opsForValue().increment(key(sku), quantity);
        return result == null ? quantity : result;
    }

    private String key(String sku) {
        return KEY_PREFIX + sku;
    }
}
