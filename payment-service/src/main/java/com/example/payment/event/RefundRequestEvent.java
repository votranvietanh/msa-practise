package com.example.payment.event;

/** Event Payment Service NHẬN (routing key: payment.refund) để thực hiện hoàn tiền - compensate action */
public class RefundRequestEvent {
    private String orderId;
    private String userId;
    private long amount;

    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public long getAmount() { return amount; }
    public void setAmount(long amount) { this.amount = amount; }
}
