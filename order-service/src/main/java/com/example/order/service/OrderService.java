package com.example.order.service;

import com.example.order.dto.CreateOrderRequest;
import com.example.order.entity.Order;
import com.example.order.entity.OrderStatus;
import com.example.order.event.OrderCreatedEvent;
import com.example.order.publisher.OrderEventPublisher;
import com.example.order.repository.OrderRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderEventPublisher publisher;

    public OrderService(OrderRepository orderRepository, OrderEventPublisher publisher) {
        this.orderRepository = orderRepository;
        this.publisher = publisher;
    }

    /**
     * Flow:
     *  1. Lưu order với status = PENDING (source of truth ngay từ đầu)
     *  2. Publish event "order.created" cho Payment Service xử lý tiếp
     *  3. Trả về orderId NGAY, không chờ Payment/Inventory xử lý xong (async)
     */
    public Order createOrder(CreateOrderRequest req) {
        // UUID.randomUUID(): sinh 1 chuỗi ngẫu nhiên gần như chắc chắn không trùng (128-bit).
        // .substring(0, 8): chỉ lấy 8 ký tự đầu cho orderId gọn, dễ đọc trong log khi demo
        // (project thật thường dùng ID tăng dần từ DB hoặc UUID đầy đủ để đảm bảo unique tuyệt đối).
        String orderId = "ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        Order order = new Order(orderId, req.getUserId(), req.getAmount(), req.getItems(), OrderStatus.PENDING);
        orderRepository.save(order);

        OrderCreatedEvent event = new OrderCreatedEvent(orderId, req.getUserId(), req.getAmount(), req.getItems());
        publisher.publishOrderCreated(event);

        return order;
    }
}
