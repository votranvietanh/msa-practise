package com.example.catalog.redis;

import java.util.List;

/**
 * Bảng xếp hạng "bán chạy nhất" - ví dụ kinh điển cho Redis Sorted Set (ZSET): 1 cấu trúc
 * dữ liệu vừa là Set (mỗi sku chỉ xuất hiện 1 lần) vừa được sắp xếp sẵn theo "score" (ở đây
 * là tổng số lượng đã bán), nên lấy "top N" luôn là thao tác O(log N) thay vì phải tự
 * SORT lại dữ liệu mỗi lần hỏi như khi dùng SQL "ORDER BY ... LIMIT N" trên bảng lớn.
 */
public interface LeaderboardService {

    void recordSale(String sku, long quantity);

    List<TopSellerEntry> topSellers(int limit);
}
