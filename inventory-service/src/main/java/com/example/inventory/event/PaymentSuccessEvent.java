package com.example.inventory.event;

import java.util.List;

/**
 * Bản định nghĩa event "payment.success" theo góc nhìn của Inventory Service.
 * "items" ở đây BẮT BUỘC phải khớp tên field với "items" mà Payment Service publish ra
 * (xem payment-service's PaymentSuccessEvent) — nếu Payment Service quên gửi field này,
 * Jackson sẽ deserialize items = null và gây NullPointerException khi InventoryRepository
 * duyệt qua items để trừ kho.
 */
public class PaymentSuccessEvent {
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

    public static class ItemDTO {
        private String sku;
        private int qty;
        public String getSku() { return sku; }
        public void setSku(String sku) { this.sku = sku; }
        public int getQty() { return qty; }
        public void setQty(int qty) { this.qty = qty; }
    }
}
