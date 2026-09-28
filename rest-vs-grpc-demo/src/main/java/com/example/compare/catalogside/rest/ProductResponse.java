package com.example.compare.catalogside.rest;

/**
 * DTO JSON phía SERVER - viết tay. Phía client (RestCatalogClient.ProductJson) phải tự
 * viết 1 bản "copy" khớp tên field với class này; không có công cụ nào bắt buộc 2 bản
 * phải khớp nhau lúc compile. So sánh: gRPC dùng chung class Product sinh từ .proto.
 */
public record ProductResponse(String sku, String name, long price, int stock) {
}
