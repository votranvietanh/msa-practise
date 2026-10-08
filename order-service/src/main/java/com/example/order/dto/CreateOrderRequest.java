package com.example.order.dto;

import com.example.order.entity.OrderItem;
import java.util.List;

/**
 * Body của request POST /orders từ Frontend gửi lên.
 *
 * CỐ Ý KHÔNG CÓ field userId: người đặt hàng được lấy từ JWT (claim "sub"), không bao giờ
 * tin 1 định danh do client tự khai trong body - nếu có field này, ai cũng đặt được đơn
 * (và bị trừ tiền ví) NHÂN DANH người khác chỉ bằng cách sửa JSON.
 */
public class CreateOrderRequest {
    private long amount;
    private List<OrderItem> items;

    public long getAmount() { return amount; }
    public void setAmount(long amount) { this.amount = amount; }
    public List<OrderItem> getItems() { return items; }
    public void setItems(List<OrderItem> items) { this.items = items; }
}
