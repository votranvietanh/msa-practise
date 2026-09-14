package com.example.catalog.redis;

/**
 * Payload JSON gửi qua kênh Pub/Sub - tách riêng khỏi message ProductUpdate (protobuf)
 * vì Pub/Sub và gRPC là 2 "giao thức" độc lập, không có lý do gì bắt payload nội bộ
 * trên Redis phải đúng y hệt cấu trúc .proto (xem RedisProductWatchBridge để thấy chỗ
 * "dịch" từ payload này sang message gRPC).
 */
public record ProductUpdatePayload(String sku, String type, long newQuantity, String message) {
}
