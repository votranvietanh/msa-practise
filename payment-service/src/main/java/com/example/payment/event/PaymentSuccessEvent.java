package com.example.payment.event;

import java.util.List;

/**
 * Event Payment Service PUBLISH khi charge thành công (routing key: payment.success).
 *
 * Event này có 2 consumer khác nhau lắng nghe cùng lúc (fan-out qua Topic Exchange):
 *  - Inventory Service (queue "inventory.queue")  -> dùng "items" để biết trừ kho SKU nào
 *  - Order Service     (queue "order.status.queue") -> chỉ dùng để log tiến độ, chưa cần "items"
 *
 * Vì Inventory Service cần "items", field này BẮT BUỘC phải có mặt ở đây — nó được lấy
 * nguyên từ OrderCreatedEvent.items mà Payment Service nhận vào (xem PaymentListener).
 */
public class PaymentSuccessEvent {
    private String orderId;
    private String userId;
    private long amount;
    private List<ItemDTO> items;

    public PaymentSuccessEvent() {}

    public PaymentSuccessEvent(String orderId, String userId, long amount, List<ItemDTO> items) {
        this.orderId = orderId;
        this.userId = userId;
        this.amount = amount;
        this.items = items;
    }

    public String getOrderId() { return orderId; }
    public String getUserId() { return userId; }
    public long getAmount() { return amount; }
    public List<ItemDTO> getItems() { return items; }

    /** 1 dòng sản phẩm trong đơn hàng: mã SKU + số lượng đặt mua */
    public static class ItemDTO {
        private String sku;
        private int qty;

        public ItemDTO() {}
        public ItemDTO(String sku, int qty) {
            this.sku = sku;
            this.qty = qty;
        }

        public String getSku() { return sku; }
        public void setSku(String sku) { this.sku = sku; }
        public int getQty() { return qty; }
        public void setQty(int qty) { this.qty = qty; }
    }
}
