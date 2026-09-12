package com.example.order.entity;

import java.time.Instant;
import java.util.List;

/**
 * Entity đại diện cho 1 order.
 * Demo dùng in-memory Map (xem InMemoryOrderRepository) thay cho JPA/DB thật,
 * để tập trung vào flow RabbitMQ thay vì setup DB.
 */
public class Order {

    private String id;
    private String userId;
    private long amount;
    private List<OrderItem> items;
    private OrderStatus status;
    private String failReason; // chỉ có giá trị khi status = FAILED
    private final Instant createdAt; // dùng để báo cáo & phát hiện order bị "kẹt" quá lâu ở PENDING

    public Order(String id, String userId, long amount, List<OrderItem> items, OrderStatus status) {
        this.id = id;
        this.userId = userId;
        this.amount = amount;
        this.items = items;
        this.status = status;
        this.createdAt = Instant.now();
    }

    public String getId() { return id; }
    public String getUserId() { return userId; }
    public long getAmount() { return amount; }
    public List<OrderItem> getItems() { return items; }
    public OrderStatus getStatus() { return status; }
    public void setStatus(OrderStatus status) { this.status = status; }
    public String getFailReason() { return failReason; }
    public void setFailReason(String failReason) { this.failReason = failReason; }
    public Instant getCreatedAt() { return createdAt; }
}
