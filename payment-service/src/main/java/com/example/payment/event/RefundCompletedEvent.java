package com.example.payment.event;

/** Event Payment Service PUBLISH sau khi hoàn tiền xong (routing key: payment.refunded) */
public class RefundCompletedEvent {
    private String orderId;

    public RefundCompletedEvent() {}
    public RefundCompletedEvent(String orderId) { this.orderId = orderId; }

    public String getOrderId() { return orderId; }
}
