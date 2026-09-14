package com.example.catalog.redis;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class RedisRateLimiter implements RateLimiter {

    private static final String KEY_PREFIX = "catalog:ratelimit:";

    private final StringRedisTemplate redisTemplate;

    public RedisRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Fixed window counter - kiểu rate limit ĐƠN GIẢN NHẤT với Redis: đếm số request trong
     * 1 "cửa sổ" thời gian cố định (vd mỗi 10 giây), request thứ (limit+1) trở đi trong cùng
     * cửa sổ bị từ chối, bộ đếm tự reset khi cửa sổ mới bắt đầu nhờ TTL của Redis (không cần
     * cron job dọn dẹp).
     *
     * GIỚI HẠN CỐ Ý ĐỂ LỘ RA: INCR và EXPIRE là 2 LỆNH RIÊNG, không atomic với nhau - nếu
     * app crash đúng giữa 2 lệnh này, key sẽ "mồ côi" TTL (sống mãi thay vì tự reset theo
     * window). Ở quy mô rate-limit (chấp nhận sai số nhỏ, hậu quả thấp) thường bỏ qua rủi ro
     * này; nếu cần chính xác tuyệt đối, gộp INCR+EXPIRE vào 1 Lua script atomic (xem
     * RedisStockService.RESERVE_SCRIPT để biết cách viết Lua script) hoặc dùng thuật toán
     * "sliding window log" bằng ZSET (lưu timestamp từng request, ZREMRANGEBYSCORE để dọn
     * các request đã cũ hơn window).
     */
    @Override
    public boolean tryAcquire(String key, int limit, Duration window) {
        String redisKey = KEY_PREFIX + key;
        Long count = redisTemplate.opsForValue().increment(redisKey);

        if (count != null && count == 1L) {
            // Chỉ set TTL ở LẦN ĐẦU key được tạo trong window này (count vừa từ 0 lên 1) -
            // nếu set lại TTL ở MỌI lần gọi, window sẽ bị "trượt" liên tục thay vì cố định.
            redisTemplate.expire(redisKey, window);
        }

        return count != null && count <= limit;
    }
}
