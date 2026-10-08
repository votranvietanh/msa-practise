package com.example.order.service;

import com.example.order.dto.OrderStatusResponse;
import com.example.order.repository.OrderRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * Tầng đọc dữ liệu riêng cho endpoint GET /orders/{id}/status - tách khỏi OrderService
 * (vốn chỉ lo việc TẠO order) để tuân theo Single Responsibility Principle: 1 class chỉ
 * nên có 1 lý do để thay đổi (ghi dữ liệu vs đọc/cache dữ liệu là 2 lý do khác nhau).
 *
 * ĐÂY LÀ USECASE REDIS CHÍNH của demo này: Frontend polling GET /orders/{id}/status định
 * kỳ (xem README) trong lúc chờ Saga chạy xong -> cùng 1 orderId có thể bị gọi hàng chục
 * lần/giây từ nhiều client. Không có cache, mỗi lần polling đều phải tính lại (ở demo này
 * là đọc Map, nhẹ; nhưng ở hệ thống thật thường là 1 query DB tốn kém) dù dữ liệu vừa mới
 * được trả về y hệt vài trăm ms trước. Cache Redis giúp giảm tải cho nguồn dữ liệu gốc mà
 * vẫn đảm bảo dữ liệu KHÔNG BỊ CŨ quá lâu nhờ 2 lớp phòng vệ:
 *   1) TTL ngắn (xem CacheConfig) - tự hết hạn dù có quên evict ở đâu đó.
 *   2) Evict thủ công ngay khi status đổi (xem evictStatusCache, được gọi từ
 *      OrderStatusListener) - đảm bảo client thấy kết quả mới NGAY sau khi Saga cập nhật
 *      xong, không phải đợi hết TTL.
 */
@Service
public class OrderQueryService {

    private final OrderRepository orderRepository;

    public OrderQueryService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    /**
     * @Cacheable(value = "orderStatus", key = "#orderId"): trước khi chạy method, Spring
     * kiểm tra Redis xem key "orderStatus::<orderId>" đã có sẵn chưa -> có thì trả thẳng
     * từ Redis, KHÔNG chạy method (cache hit). Chưa có thì chạy method như bình thường rồi
     * mới lưu kết quả vào Redis trước khi trả về (cache miss).
     *
     * unless = "#result == null": nếu order chưa tồn tại (chưa kịp tạo, hoặc id sai),
     * KHÔNG cache giá trị null - tránh trường hợp poll sớm 1 chút trước khi order được tạo
     * xong rồi bị cache "not found" nhầm trong khi order đã tồn tại ngay sau đó.
     */
    @Cacheable(value = "orderStatus", key = "#orderId", unless = "#result == null")
    public OrderStatusResponse getStatus(String orderId) {
        return orderRepository.findById(orderId)
                .map(order -> new OrderStatusResponse(order.getStatus().name(), order.getFailReason()))
                .orElse(null);
    }

    /**
     * Ai là chủ của đơn này - để OrderController chặn người dùng xem đơn của người khác
     * (lỗi IDOR: chỉ cần đoán/đổi orderId trên URL là đọc được dữ liệu người khác nếu server
     * chỉ kiểm tra "đã đăng nhập" mà quên kiểm tra "có phải đơn của mình không").
     *
     * Cache riêng vì chủ đơn KHÔNG BAO GIỜ đổi sau khi tạo - không cần evict, và mỗi lần
     * polling status đều phải kiểm tra quyền nên đây cũng là lời gọi nóng không kém getStatus.
     */
    @Cacheable(value = "orderOwner", key = "#orderId", unless = "#result == null")
    public String getOwnerId(String orderId) {
        return orderRepository.findById(orderId)
                .map(order -> order.getUserId())
                .orElse(null);
    }

    /**
     * Method này KHÔNG có logic bên trong - bản thân annotation @CacheEvict mới là thứ
     * thực sự xoá key khỏi Redis. Gọi method rỗng này là cách chuẩn để "nhờ" Spring AOP
     * proxy chặn lại và chạy hành vi evict, giống hệt cách @Cacheable chặn lại getStatus().
     */
    @CacheEvict(value = "orderStatus", key = "#orderId")
    public void evictStatusCache(String orderId) {
        // no-op: xem giải thích ở javadoc phía trên
    }
}
