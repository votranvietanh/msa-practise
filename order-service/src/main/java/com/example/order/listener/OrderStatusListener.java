package com.example.order.listener;

import com.example.order.entity.Order;
import com.example.order.entity.OrderStatus;
import com.example.order.event.*;
import com.example.order.publisher.OrderEventPublisher;
import com.example.order.repository.OrderRepository;
import com.example.order.service.OrderQueryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Lắng nghe TẤT CẢ event kết quả cuối trên "order.status.queue"
 * (payment.success / payment.failed / payment.refunded / inventory.reserved / inventory.failed).
 *
 * Vì queue này nhận NHIỀU loại routing key khác nhau, ta KHÔNG THỂ khai báo tham số method
 * là 1 class event cụ thể (khác với PaymentListener/InventoryListener chỉ nhận đúng 1 loại
 * event nên Spring tự convert JSON -> POJO được luôn). Ở đây phải nhận raw `Message` (chưa
 * convert), tự đọc routing key từ message header rồi mới biết nên parse JSON thành class
 * event nào — dùng 1 ObjectMapper thủ công thay vì bean Jackson2JsonMessageConverter vì
 * converter đó chỉ hoạt động khi @RabbitListener biết trước kiểu POJO cần convert tới.
 */
@Component
public class OrderStatusListener {

    private final OrderRepository orderRepository;
    private final OrderEventPublisher publisher;
    private final OrderQueryService orderQueryService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OrderStatusListener(OrderRepository orderRepository, OrderEventPublisher publisher,
                                OrderQueryService orderQueryService) {
        this.orderRepository = orderRepository;
        this.publisher = publisher;
        this.orderQueryService = orderQueryService;
    }

    @RabbitListener(queues = "order.status.queue")
    public void handle(Message message,
                        Channel channel,
                        @Header(AmqpHeaders.DELIVERY_TAG) long tag) throws IOException {

        String routingKey = message.getMessageProperties().getReceivedRoutingKey();
        byte[] body = message.getBody();

        try {
            switch (routingKey) {

                case "payment.failed" -> {
                    PaymentFailedEvent e = objectMapper.readValue(body, PaymentFailedEvent.class);
                    updateStatus(e.getOrderId(), OrderStatus.FAILED, e.getReason());
                    System.out.println("[OrderStatusListener] payment.failed -> order "
                            + e.getOrderId() + " FAILED (" + e.getReason() + ")");
                }

                case "payment.success" -> {
                    // Order Service chỉ cần biết để log/track tiến độ, chưa cập nhật status cuối ở đây
                    PaymentSuccessEvent e = objectMapper.readValue(body, PaymentSuccessEvent.class);
                    System.out.println("[OrderStatusListener] payment.success received for order "
                            + e.getOrderId() + " (chờ inventory xử lý tiếp)");
                }

                case "inventory.reserved" -> {
                    InventoryReservedEvent e = objectMapper.readValue(body, InventoryReservedEvent.class);
                    updateStatus(e.getOrderId(), OrderStatus.COMPLETED, null);
                    System.out.println("[OrderStatusListener] inventory.reserved -> order "
                            + e.getOrderId() + " COMPLETED");
                }

                case "inventory.failed" -> {
                    InventoryFailedEvent e = objectMapper.readValue(body, InventoryFailedEvent.class);
                    updateStatus(e.getOrderId(), OrderStatus.FAILED, e.getReason());

                    // COMPENSATE: tiền đã bị trừ ở bước Payment, giờ phải hoàn lại
                    publisher.publishRefundRequest(
                            new RefundRequestEvent(e.getOrderId(), e.getUserId(), e.getAmount()));

                    System.out.println("[OrderStatusListener] inventory.failed -> order "
                            + e.getOrderId() + " FAILED, published payment.refund");
                }

                case "payment.refunded" -> {
                    // Bước compensate cuối cùng đã xong. Order đã là FAILED từ trước (case
                    // inventory.failed ở trên) nên KHÔNG cần đổi status nữa - chỉ log lại để
                    // audit "đơn này đã được hoàn tiền thành công", tránh event bị "rơi mất"
                    // như trước khi routing key này chưa được bind vào order.status.queue.
                    RefundCompletedEvent e = objectMapper.readValue(body, RefundCompletedEvent.class);
                    System.out.println("[OrderStatusListener] payment.refunded -> order "
                            + e.getOrderId() + " đã hoàn tiền xong (status vẫn FAILED)");
                }

                default -> System.out.println("[OrderStatusListener] Unhandled routing key: " + routingKey);
            }

            channel.basicAck(tag, false);

        } catch (Exception ex) {
            // Lỗi hệ thống (parse fail, DB lỗi...) -> đẩy sang DLQ, không loop vô hạn
            channel.basicNack(tag, false, false);
        }
    }

    /**
     * Gọi lại orderRepository.save(order) sau khi mutate thay vì chỉ sửa field trên object
     * lấy được từ findById(): với repository in-memory hiện tại thì 2 cách cho kết quả y hệt
     * (vì Map đang giữ tham chiếu tới cùng 1 object), NHƯNG nếu sau này đổi sang JPA/DB
     * thật thì bắt buộc phải gọi save() để flush thay đổi xuống DB. Gọi save() tường minh
     * ngay từ bây giờ giúp code không "ăn may" nhờ chi tiết cài đặt hiện tại.
     *
     * evictStatusCache(): xoá ngay cache Redis của order này (xem OrderQueryService) để
     * lần GET /orders/{id}/status tiếp theo đọc được status MỚI, không phải đợi hết TTL.
     */
    private void updateStatus(String orderId, OrderStatus status, String reason) {
        orderRepository.findById(orderId).ifPresent(order -> {
            order.setStatus(status);
            order.setFailReason(reason);
            orderRepository.save(order);
        });
        orderQueryService.evictStatusCache(orderId);
    }
}
