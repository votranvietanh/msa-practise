package com.example.inventory.repository;

import com.example.inventory.event.PaymentSuccessEvent;

import java.util.List;

/**
 * Interface (Dependency Inversion) để InventoryListener phụ thuộc vào 1 hợp đồng trừu
 * tượng thay vì cách lưu kho thật sự bên dưới - dễ mock khi unit test, dễ đổi sang DB
 * thật (vd PostgreSQL + SELECT ... FOR UPDATE) sau này mà không phải sửa listener.
 */
public interface InventoryRepository {

    /**
     * @return true nếu trừ kho thành công cho TẤT CẢ item trong order (hoặc order này đã
     * từng trừ thành công trước đó - idempotent), false nếu bất kỳ item nào hết hàng.
     */
    boolean tryReserve(String orderId, List<PaymentSuccessEvent.ItemDTO> items);
}
