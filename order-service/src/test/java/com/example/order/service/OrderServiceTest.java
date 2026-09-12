package com.example.order.service;

import com.example.order.dto.CreateOrderRequest;
import com.example.order.entity.Order;
import com.example.order.entity.OrderStatus;
import com.example.order.event.OrderCreatedEvent;
import com.example.order.publisher.OrderEventPublisher;
import com.example.order.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock private OrderRepository orderRepository;
    @Mock private OrderEventPublisher publisher;

    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderService = new OrderService(orderRepository, publisher);
    }

    @Test
    void createOrder_luuOrderVoiStatusPending_vaPublishOrderCreated() {
        CreateOrderRequest request = new CreateOrderRequest();
        request.setUserId("U001");
        request.setAmount(250_000L);
        request.setItems(List.of());

        Order order = orderService.createOrder(request);

        assertThat(order.getId()).startsWith("ORD-");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.getUserId()).isEqualTo("U001");

        verify(orderRepository).save(order);

        ArgumentCaptor<OrderCreatedEvent> captor = ArgumentCaptor.forClass(OrderCreatedEvent.class);
        verify(publisher).publishOrderCreated(captor.capture());
        assertThat(captor.getValue().getOrderId()).isEqualTo(order.getId());
        assertThat(captor.getValue().getAmount()).isEqualTo(250_000L);
    }
}
