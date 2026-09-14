package com.example.catalog.redis;

/** 1 dòng trong bảng xếp hạng bán chạy */
public record TopSellerEntry(String sku, long soldCount) {
}
