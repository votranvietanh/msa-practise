package com.example.catalog.repository;

import com.example.catalog.entity.Product;

import java.util.Optional;

/**
 * Interface (Dependency Inversion, giống toàn bộ repo order-saga-demo) - lưu METADATA
 * sản phẩm (tên, giá). Tồn kho KHÔNG nằm ở đây - xem redis/StockService để hiểu tại sao
 * 2 loại dữ liệu này cố tình tách ra 2 nơi lưu trữ khác nhau.
 */
public interface ProductRepository {

    Product save(Product product);

    Optional<Product> findBySku(String sku);

    boolean existsBySku(String sku);
}
