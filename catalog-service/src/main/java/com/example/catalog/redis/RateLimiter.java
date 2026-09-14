package com.example.catalog.redis;

import java.time.Duration;

/** Giới hạn số lần 1 "key" (vd tên API, hoặc userId/API key thật) được gọi trong 1 khoảng thời gian. */
public interface RateLimiter {

    /** @return true nếu request này được PHÉP đi tiếp, false nếu đã vượt giới hạn */
    boolean tryAcquire(String key, int limit, Duration window);
}
