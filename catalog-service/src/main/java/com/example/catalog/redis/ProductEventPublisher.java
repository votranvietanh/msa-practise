package com.example.catalog.redis;

/**
 * Publish (bên "P" của Pub/Sub) cập nhật về 1 sản phẩm. Đối lập với RedisProductWatchBridge
 * (bên "S" - subscribe). Interface tách riêng khỏi publisher cụ thể để StockService/gRPC
 * service không cần biết cơ chế publish là Redis Pub/Sub hay thứ gì khác.
 */
public interface ProductEventPublisher {

    void publish(String sku, String type, long newQuantity, String message);
}
