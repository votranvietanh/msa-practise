package com.example.order.dto;

/** Response trả về ngay cho Frontend sau khi tạo order (202 Accepted) */
public class OrderResponse {
    private String orderId;
    private String status;

    public OrderResponse(String orderId, String status) {
        this.orderId = orderId;
        this.status = status;
    }

    public String getOrderId() { return orderId; }
    public String getStatus() { return status; }
}
