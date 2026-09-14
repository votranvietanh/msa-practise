package com.example.catalog.redis;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Service
public class RedisLeaderboardService implements LeaderboardService {

    private static final String LEADERBOARD_KEY = "catalog:leaderboard:bestseller";

    private final StringRedisTemplate redisTemplate;

    public RedisLeaderboardService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * ZINCRBY: cộng dồn "score" (ở đây là số lượng đã bán) cho member (sku) trong sorted
     * set, tự tạo member với score = quantity nếu chưa từng tồn tại. Atomic, không cần
     * đọc-rồi-ghi thủ công như khi dùng SQL "UPDATE ... SET sold = sold + ?".
     */
    @Override
    public void recordSale(String sku, long quantity) {
        redisTemplate.opsForZSet().incrementScore(LEADERBOARD_KEY, sku, quantity);
    }

    /**
     * ZREVRANGE ... WITHSCORES: lấy top N member có score CAO NHẤT, kèm luôn score, theo
     * đúng thứ tự xếp hạng - Redis tự duy trì thứ tự này mỗi lần ZINCRBY, không cần sort
     * lại ở tầng application.
     */
    @Override
    public List<TopSellerEntry> topSellers(int limit) {
        Set<ZSetOperations.TypedTuple<String>> topEntries =
                redisTemplate.opsForZSet().reverseRangeWithScores(LEADERBOARD_KEY, 0, limit - 1);

        if (topEntries == null) {
            return List.of();
        }

        return topEntries.stream()
                .map(entry -> new TopSellerEntry(entry.getValue(), scoreToLong(entry.getScore())))
                .toList();
    }

    private long scoreToLong(Double score) {
        return score == null ? 0L : score.longValue();
    }
}
