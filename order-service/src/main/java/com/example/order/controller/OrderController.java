package com.example.order.controller;

import com.example.order.dto.CreateOrderRequest;
import com.example.order.dto.OrderResponse;
import com.example.order.dto.OrderStatusResponse;
import com.example.order.entity.Order;
import com.example.order.service.OrderQueryService;
import com.example.order.service.OrderService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Entry point duy nhất từ Frontend (REST, đồng bộ).
 * Toàn bộ xử lý Saga phía sau là bất đồng bộ qua RabbitMQ - Frontend không cần biết.
 */
@RestController
@RequestMapping("/orders")
public class OrderController {

    private final OrderService orderService;
    private final OrderQueryService orderQueryService;

    public OrderController(OrderService orderService, OrderQueryService orderQueryService) {
        this.orderService = orderService;
        this.orderQueryService = orderQueryService;
    }

    /**
     * INPUT:  POST /orders  { "userId": "U001", "amount": 250000, "items": [...] }
     * OUTPUT: 202 Accepted  { "orderId": "ORD-XXXX", "status": "PENDING" }
     *
     * 202 Accepted (khác 200 OK) nghĩa là "server đã NHẬN yêu cầu và sẽ xử lý, nhưng
     * chưa xử lý xong". Đây là mã HTTP đúng chuẩn cho các API kích hoạt xử lý bất đồng bộ
     * (ở đây là cả chuỗi Saga chạy ngầm qua RabbitMQ) - Frontend không được hiểu 202 là
     * "đơn hàng đã hoàn tất", mà phải tiếp tục gọi GET /orders/{id}/status để biết kết quả.
     */
    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(@RequestBody CreateOrderRequest req) {
        Order order = orderService.createOrder(req);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new OrderResponse(order.getId(), order.getStatus().name()));
    }

    /**
     * Frontend gọi định kỳ (polling) để biết Saga đã xử lý xong chưa.
     * INPUT:  GET /orders/ORD-XXXX/status
     * OUTPUT: { "status": "PENDING" | "COMPLETED" | "FAILED", "failReason": "..." }
     *
     * Đọc qua OrderQueryService (có cache Redis phía sau) thay vì đọc thẳng repository,
     * vì đây chính là endpoint bị Frontend gọi dồn dập nhất trong toàn hệ thống (polling).
     */
    @GetMapping("/{id}/status")
    public ResponseEntity<OrderStatusResponse> getStatus(@PathVariable String id) {
        OrderStatusResponse status = orderQueryService.getStatus(id);
        return status != null ? ResponseEntity.ok(status) : ResponseEntity.notFound().build();
    }
}
