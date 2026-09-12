package com.example.order.event;

import com.example.order.entity.OrderItem;
import java.util.List;

/**
 * Event được Order Service PUBLISH với routing key "order.created".
 * Payment Service sẽ nhận đúng schema này (mỗi service có bản định nghĩa event riêng
 * dù nội dung field giống nhau - tránh coupling schema trực tiếp giữa các service).
 */
public class OrderCreatedEvent {
    private String orderId;
    private String userId;
    private long amount;
    private List<OrderItem> items;

    public OrderCreatedEvent() {}
    public OrderCreatedEvent(String orderId, String userId, long amount, List<OrderItem> items) {
        this.orderId = orderId;
        this.userId = userId;
        this.amount = amount;
        this.items = items;
    }

    public String getOrderId() { return orderId; }
    public String getUserId() { return userId; }
    public long getAmount() { return amount; }
    public List<OrderItem> getItems() { return items; }
}
