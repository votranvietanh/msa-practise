package com.example.payment.event;

import java.util.List;

/**
 * Bản định nghĩa event "order.created" theo góc nhìn của Payment Service
 * (Anti-Corruption Layer: mỗi service tự khai báo schema của event nó nhận,
 * không import chung 1 class DTO với Order Service dù nội dung giống nhau).
 *
 * Payment Service tự nó KHÔNG dùng "items" để charge tiền (chỉ cần userId + amount),
 * nhưng vẫn phải GIỮ LẠI field này để chuyển tiếp (relay) cho Inventory Service ở bước
 * sau qua PaymentSuccessEvent — nếu bỏ field này, Inventory Service sẽ không biết trừ
 * kho sản phẩm nào (đây từng là 1 bug thực tế trong bản demo: field "items" bị rớt
 * mất giữa order.created -> payment.success, khiến Inventory Service luôn lỗi).
 */
public class OrderCreatedEvent {
    private String orderId;
    private String userId;
    private long amount;
    private List<ItemDTO> items;

    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public long getAmount() { return amount; }
    public void setAmount(long amount) { this.amount = amount; }
    public List<ItemDTO> getItems() { return items; }
    public void setItems(List<ItemDTO> items) { this.items = items; }

    /** 1 dòng sản phẩm trong đơn hàng: mã SKU + số lượng đặt mua */
    public static class ItemDTO {
        private String sku;
        private int qty;

        public String getSku() { return sku; }
        public void setSku(String sku) { this.sku = sku; }
        public int getQty() { return qty; }
        public void setQty(int qty) { this.qty = qty; }
    }
}
