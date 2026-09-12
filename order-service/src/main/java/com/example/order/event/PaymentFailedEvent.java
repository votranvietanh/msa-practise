package com.example.order.event;

/** Event Order Service NHẬN khi Payment Service báo thất bại (routing key: payment.failed) */
public class PaymentFailedEvent {
    private String orderId;
    private String reason;

    public PaymentFailedEvent() {}
    public PaymentFailedEvent(String orderId, String reason) {
        this.orderId = orderId;
        this.reason = reason;
    }

    public String getOrderId() { return orderId; }
    public String getReason() { return reason; }
}
