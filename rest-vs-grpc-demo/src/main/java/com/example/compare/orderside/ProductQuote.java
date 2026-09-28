package com.example.compare.orderside;

/** Thông tin sản phẩm theo góc nhìn của Order Service (model riêng, không dùng class của Catalog). */
public record ProductQuote(String sku, String name, long price, int stock) {
}
