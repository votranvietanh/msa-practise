package com.example.order.repository;

import com.example.order.entity.Order;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implementation bằng in-memory Map, thay cho JpaRepository thật.
 * Trong dự án thực tế đây sẽ là: `public interface OrderJpaRepository extends
 * JpaRepository<Order, String> {}` (Spring Data JPA tự sinh implementation).
 *
 * Class này CỐ TÌNH không chứa logic cache (@Cacheable) - caching là 1 concern của
 * tầng đọc dữ liệu cho client (xem OrderQueryService), không phải của tầng lưu trữ.
 * Tách riêng như vậy giúp repository chỉ có đúng 1 lý do để thay đổi: cách dữ liệu được
 * lưu (Single Responsibility Principle).
 */
@Repository
public class InMemoryOrderRepository implements OrderRepository {

    private final Map<String, Order> store = new ConcurrentHashMap<>();

    @Override
    public Order save(Order order) {
        store.put(order.getId(), order);
        return order;
    }

    @Override
    public Optional<Order> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public List<Order> findAll() {
        return List.copyOf(store.values());
    }
}
