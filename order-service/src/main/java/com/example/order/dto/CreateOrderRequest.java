package com.example.order.dto;

import com.example.order.entity.OrderItem;
import java.util.List;

/** Body của request POST /orders từ Frontend gửi lên */
public class CreateOrderRequest {
    private String userId;
    private long amount;
    private List<OrderItem> items;

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public long getAmount() { return amount; }
    public void setAmount(long amount) { this.amount = amount; }
    public List<OrderItem> getItems() { return items; }
    public void setItems(List<OrderItem> items) { this.items = items; }
}
