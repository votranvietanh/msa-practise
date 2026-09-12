package com.example.order.event;

/**
 * Event Order Service NHẬN khi Payment Service xác nhận đã hoàn tiền xong
 * (routing key: payment.refunded). Đây là bước cuối cùng của compensating transaction:
 * order đã bị đánh FAILED từ bước inventory.failed rồi, event này chỉ để Order Service
 * biết "tiền đã thực sự về lại ví user", phục vụ log/audit chứ không đổi status nữa.
 */
public class RefundCompletedEvent {
    private String orderId;

    public RefundCompletedEvent() {}
    public RefundCompletedEvent(String orderId) { this.orderId = orderId; }

    public String getOrderId() { return orderId; }
}
