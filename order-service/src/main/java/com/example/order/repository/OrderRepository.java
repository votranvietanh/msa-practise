package com.example.order.repository;

import com.example.order.entity.Order;

import java.util.List;
import java.util.Optional;

/**
 * Interface (không phải class cụ thể) để tầng service/listener PHỤ THUỘC vào một
 * "hợp đồng" trừu tượng thay vì phụ thuộc thẳng vào cách lưu trữ thật sự bên dưới
 * (Dependency Inversion Principle - chữ D trong SOLID).
 *
 * Lợi ích thực tế:
 *  1) Dễ viết unit test: mock interface này bằng Mockito, không cần khởi động DB thật.
 *  2) Dễ đổi implementation sau này (vd chuyển từ InMemoryOrderRepository sang
 *     JpaOrderRepository dùng PostgreSQL) mà KHÔNG cần sửa OrderService/OrderStatusListener,
 *     vì chúng chỉ biết tới interface này, không biết chi tiết implementation.
 */
public interface OrderRepository {

    Order save(Order order);

    Optional<Order> findById(String id);

    /** Dùng cho báo cáo (report) và đối soát (reconciliation) - cần duyệt qua toàn bộ order */
    List<Order> findAll();
}
