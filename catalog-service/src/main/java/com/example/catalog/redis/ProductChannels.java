package com.example.catalog.redis;

/**
 * Công thức đặt tên kênh Pub/Sub, dùng CHUNG bởi cả publisher (RedisProductEventPublisher)
 * và subscriber (RedisProductWatchBridge) - tách ra 1 chỗ duy nhất để tránh lỗi kiểu
 * "publisher gửi vào kênh product:ITEM-01 nhưng subscriber lại lắng nghe channel:ITEM-01"
 * (2 bên tự viết công thức riêng rồi lệch nhau, rất khó phát hiện vì không có lỗi biên dịch
 * hay lỗi runtime nào cả - message chỉ đơn giản không bao giờ tới nơi).
 */
final class ProductChannels {
    private ProductChannels() {}

    static String forSku(String sku) {
        return "catalog:product:" + sku;
    }
}
