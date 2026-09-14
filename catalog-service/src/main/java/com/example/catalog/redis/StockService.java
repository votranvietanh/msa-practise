package com.example.catalog.redis;

/**
 * Quản lý TỒN KHO (con số hay đổi liên tục) - cố tình tách khỏi ProductRepository
 * (metadata sản phẩm, ít đổi). Đây là ranh giới thường gặp trong hệ thống thật: dữ liệu
 * đổi liên tục, cần atomic increment/decrement, tốc độ cao -> hợp với Redis hơn hẳn 1
 * bảng SQL (phải SELECT ... FOR UPDATE mới atomic được, chậm hơn nhiều so với lệnh Redis
 * đơn atomic sẵn).
 */
public interface StockService {

    void initStock(String sku, long quantity);

    long getStock(String sku);

    /**
     * Trừ kho ATOMIC: kiểm tra đủ hàng rồi trừ trong ĐÚNG 1 lệnh Redis (Lua script),
     * không có khoảng hở giữa "đọc" và "ghi" để 2 request chen vào nhau.
     * @return tồn kho còn lại sau khi trừ, hoặc -1 nếu không đủ hàng (không trừ gì cả)
     */
    long tryReserve(String sku, long quantity);

    long restock(String sku, long quantity);
}
