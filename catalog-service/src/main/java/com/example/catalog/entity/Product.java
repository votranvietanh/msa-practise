package com.example.catalog.entity;

/**
 * Model nghiệp vụ nội bộ - KHÁC với com.example.catalog.grpc.ProductDto (class do
 * protobuf-maven-plugin tự sinh ra từ catalog.proto). Tách riêng 2 class dù field giống
 * nhau, cùng lý do Anti-Corruption Layer đã áp dụng ở order-saga-demo: đổi wire format
 * (.proto) không bắt buộc đổi model nội bộ, và ngược lại.
 */
public class Product {
    private final String sku;
    private final String name;
    private final long price;

    public Product(String sku, String name, long price) {
        this.sku = sku;
        this.name = name;
        this.price = price;
    }

    public String getSku() { return sku; }
    public String getName() { return name; }
    public long getPrice() { return price; }
}
