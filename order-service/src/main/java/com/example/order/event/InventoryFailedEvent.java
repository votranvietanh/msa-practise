package com.example.order.event;

/**
 * Event Order Service NHẬN khi Inventory Service hết hàng (routing key: inventory.failed).
 * Kèm userId + amount để Order Service có đủ thông tin publish tiếp event "payment.refund"
 * (compensating transaction - hoàn tiền lại vì tiền đã bị trừ ở bước Payment trước đó).
 */
public class InventoryFailedEvent {
    private String orderId;
    private String userId;
    private long amount;
    private String reason;

    public InventoryFailedEvent() {}
    public InventoryFailedEvent(String orderId, String userId, long amount, String reason) {
        this.orderId = orderId;
        this.userId = userId;
        this.amount = amount;
        this.reason = reason;
    }

    public String getOrderId() { return orderId; }
    public String getUserId() { return userId; }
    public long getAmount() { return amount; }
    public String getReason() { return reason; }
}
