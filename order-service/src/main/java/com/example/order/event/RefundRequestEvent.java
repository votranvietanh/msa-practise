package com.example.order.event;

/**
 * Event Order Service PUBLISH với routing key "payment.refund" -- bước COMPENSATE của Saga.
 * Payment Service sẽ lắng nghe event này để hoàn tiền lại cho user.
 */
public class RefundRequestEvent {
    private String orderId;
    private String userId;
    private long amount;

    public RefundRequestEvent() {}
    public RefundRequestEvent(String orderId, String userId, long amount) {
        this.orderId = orderId;
        this.userId = userId;
        this.amount = amount;
    }

    public String getOrderId() { return orderId; }
    public String getUserId() { return userId; }
    public long getAmount() { return amount; }
}
