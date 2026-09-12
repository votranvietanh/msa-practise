package com.example.order.listener;

import com.example.order.entity.Order;
import com.example.order.entity.OrderItem;
import com.example.order.entity.OrderStatus;
import com.example.order.event.RefundRequestEvent;
import com.example.order.publisher.OrderEventPublisher;
import com.example.order.repository.OrderRepository;
import com.example.order.service.OrderQueryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;

/**
 * Test method handle(Message, Channel, tag) bằng cách tự dựng raw Message với routing key
 * cụ thể - đúng với cách RabbitMQ thật sự truyền message vào method này (xem comment trong
 * OrderStatusListener về lý do dùng raw Message thay vì 1 class event cụ thể).
 */
@ExtendWith(MockitoExtension.class)
class OrderStatusListenerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock private OrderRepository orderRepository;
    @Mock private OrderEventPublisher publisher;
    @Mock private OrderQueryService orderQueryService;
    @Mock private Channel channel;

    private OrderStatusListener listener;

    @BeforeEach
    void setUp() {
        listener = new OrderStatusListener(orderRepository, publisher, orderQueryService);
    }

    private Message message(String routingKey, Object payload) throws Exception {
        MessageProperties props = new MessageProperties();
        props.setReceivedRoutingKey(routingKey);
        return new Message(objectMapper.writeValueAsBytes(payload), props);
    }

    @Test
    void inventoryReserved_thiCapNhatCompleted_vaEvictCache() throws Exception {
        Order order = new Order("ORD-1", "U001", 250_000L, List.of(new OrderItem("ITEM-01", 1)), OrderStatus.PENDING);
        when(orderRepository.findById("ORD-1")).thenReturn(Optional.of(order));

        Message msg = message("inventory.reserved", new InventoryReservedPayload("ORD-1"));
        listener.handle(msg, channel, 1L);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.COMPLETED);
        verify(orderRepository).save(order);
        verify(orderQueryService).evictStatusCache("ORD-1");
        verify(channel).basicAck(1L, false);
    }

    @Test
    void paymentFailed_thiCapNhatFailed_khongTriggerRefund() throws Exception {
        Order order = new Order("ORD-2", "U001", 250_000L, List.of(), OrderStatus.PENDING);
        when(orderRepository.findById("ORD-2")).thenReturn(Optional.of(order));

        Message msg = message("payment.failed", new PaymentFailedPayload("ORD-2", "INSUFFICIENT_BALANCE"));
        listener.handle(msg, channel, 2L);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);
        assertThat(order.getFailReason()).isEqualTo("INSUFFICIENT_BALANCE");
        verify(publisher, never()).publishRefundRequest(any());
        verify(orderQueryService).evictStatusCache("ORD-2");
        verify(channel).basicAck(2L, false);
    }

    @Test
    void inventoryFailed_thiCapNhatFailed_vaTriggerCompensateRefund() throws Exception {
        Order order = new Order("ORD-3", "U001", 250_000L, List.of(), OrderStatus.PENDING);
        when(orderRepository.findById("ORD-3")).thenReturn(Optional.of(order));

        Message msg = message("inventory.failed", new InventoryFailedPayload("ORD-3", "U001", 250_000L, "OUT_OF_STOCK"));
        listener.handle(msg, channel, 3L);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);

        ArgumentCaptor<RefundRequestEvent> captor = ArgumentCaptor.forClass(RefundRequestEvent.class);
        verify(publisher).publishRefundRequest(captor.capture());
        assertThat(captor.getValue().getOrderId()).isEqualTo("ORD-3");
        assertThat(captor.getValue().getAmount()).isEqualTo(250_000L);

        verify(orderQueryService).evictStatusCache("ORD-3");
        verify(channel).basicAck(3L, false);
    }

    @Test
    void paymentRefunded_chiLog_khongDongStatusHayEvict() throws Exception {
        Message msg = message("payment.refunded", new RefundCompletedPayload("ORD-4"));
        listener.handle(msg, channel, 4L);

        verify(orderRepository, never()).save(any());
        verify(orderQueryService, never()).evictStatusCache(any());
        verify(channel).basicAck(4L, false);
    }

    @Test
    void routingKeyLa_thiVanAckKhongCrash() throws Exception {
        MessageProperties props = new MessageProperties();
        props.setReceivedRoutingKey("some.unknown.key");
        Message msg = new Message("{}".getBytes(), props);

        listener.handle(msg, channel, 5L);

        verify(channel).basicAck(5L, false);
    }

    @Test
    void bodyKhongParseDuoc_thiNackKhongRequeue() throws Exception {
        MessageProperties props = new MessageProperties();
        props.setReceivedRoutingKey("inventory.reserved");
        Message msg = new Message("{invalid-json".getBytes(), props);

        listener.handle(msg, channel, 6L);

        verify(channel).basicNack(6L, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    // Payload record đơn giản chỉ để phục vụ serialize JSON đúng field name cho test,
    // không phải class thật của production (production dùng com.example.order.event.*).
    private record InventoryReservedPayload(String orderId) {}
    private record PaymentFailedPayload(String orderId, String reason) {}
    private record InventoryFailedPayload(String orderId, String userId, long amount, String reason) {}
    private record RefundCompletedPayload(String orderId) {}
}
