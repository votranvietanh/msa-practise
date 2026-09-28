package com.example.compare.catalogside;

/** Model nghiệp vụ nội bộ của Catalog Service - KHÔNG đi ra ngoài mạng trực tiếp. */
public record ProductInfo(String sku, String name, long price, int stock) {
}
