package com.example.order.event;

/** Event Order Service NHẬN (qua order.status.queue) khi Payment Service báo thành công */
public class PaymentSuccessEvent {
    private String orderId;

    public PaymentSuccessEvent() {}
    public PaymentSuccessEvent(String orderId) { this.orderId = orderId; }

    public String getOrderId() { return orderId; }
}
