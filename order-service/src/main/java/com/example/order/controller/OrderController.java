package com.example.order.controller;

import com.example.order.dto.CreateOrderRequest;
import com.example.order.dto.OrderResponse;
import com.example.order.dto.OrderStatusResponse;
import com.example.order.entity.Order;
import com.example.order.service.OrderQueryService;
import com.example.order.service.OrderService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/**
 * Entry point duy nhất từ Frontend (REST, đồng bộ).
 * Toàn bộ xử lý Saga phía sau là bất đồng bộ qua RabbitMQ - Frontend không cần biết.
 *
 * Mọi endpoint ở đây đã được SecurityConfig bắt buộc phải có JWT hợp lệ TRƯỚC KHI vào tới
 * controller - code trong này chỉ lo phần "người này được làm gì với dữ liệu NÀY" (phân
 * quyền theo dữ liệu), không phải "người này là ai" (xác thực).
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
     * INPUT:  POST /orders  { "amount": 250000, "items": [...] }   + header Authorization: Bearer <JWT>
     * OUTPUT: 202 Accepted  { "orderId": "ORD-XXXX", "status": "PENDING" }
     *
     * @AuthenticationPrincipal Jwt: Spring Security đã kiểm tra chữ ký/hạn của token và
     * đưa nội dung đã xác thực vào đây. jwt.getSubject() là userId do auth-service ký -
     * không thể bị client sửa (sửa là hỏng chữ ký, bị từ chối ngay ở filter).
     *
     * 202 Accepted (khác 200 OK) nghĩa là "server đã NHẬN yêu cầu và sẽ xử lý, nhưng
     * chưa xử lý xong". Đây là mã HTTP đúng chuẩn cho các API kích hoạt xử lý bất đồng bộ
     * (ở đây là cả chuỗi Saga chạy ngầm qua RabbitMQ) - Frontend không được hiểu 202 là
     * "đơn hàng đã hoàn tất", mà phải tiếp tục gọi GET /orders/{id}/status để biết kết quả.
     */
    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(@AuthenticationPrincipal Jwt jwt,
                                                      @RequestBody CreateOrderRequest req) {
        Order order = orderService.createOrder(jwt.getSubject(), req);
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
     *
     * Đơn của người khác trả 404 (không phải 403): 403 vô tình xác nhận "đơn này TỒN TẠI,
     * chỉ là không phải của bạn" - cho phép dò xem orderId nào có thật.
     */
    @GetMapping("/{id}/status")
    public ResponseEntity<OrderStatusResponse> getStatus(@PathVariable String id, Authentication authentication) {
        String ownerId = orderQueryService.getOwnerId(id);
        if (ownerId == null || !canView(authentication, ownerId)) {
            return ResponseEntity.notFound().build();
        }

        OrderStatusResponse status = orderQueryService.getStatus(id);
        return status != null ? ResponseEntity.ok(status) : ResponseEntity.notFound().build();
    }

    private boolean canView(Authentication authentication, String ownerId) {
        boolean isAdmin = authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        return isAdmin || ownerId.equals(authentication.getName());
    }
}
