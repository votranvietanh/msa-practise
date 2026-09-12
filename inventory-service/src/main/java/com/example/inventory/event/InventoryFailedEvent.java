package com.example.inventory.event;

/** Event Inventory Service PUBLISH khi hết hàng (routing key: inventory.failed) */
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
