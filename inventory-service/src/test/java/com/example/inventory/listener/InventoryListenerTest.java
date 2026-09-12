package com.example.inventory.listener;

import com.example.inventory.config.RabbitConfig;
import com.example.inventory.event.InventoryFailedEvent;
import com.example.inventory.event.InventoryReservedEvent;
import com.example.inventory.event.PaymentSuccessEvent;
import com.example.inventory.repository.InventoryRepository;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.io.IOException;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InventoryListenerTest {

    @Mock private RabbitTemplate rabbitTemplate;
    @Mock private InventoryRepository inventoryRepository;
    @Mock private Channel channel;

    private InventoryListener listener;

    @BeforeEach
    void setUp() {
        listener = new InventoryListener(rabbitTemplate, inventoryRepository);
    }

    private PaymentSuccessEvent event(String orderId) {
        PaymentSuccessEvent event = new PaymentSuccessEvent();
        event.setOrderId(orderId);
        event.setUserId("U001");
        event.setAmount(250_000L);
        event.setItems(List.of());
        return event;
    }

    @Test
    void conHang_thiPublishInventoryReserved() throws IOException {
        when(inventoryRepository.tryReserve(eq("ORD-1"), anyList())).thenReturn(true);

        listener.handlePaymentSuccess(event("ORD-1"), channel, 1L);

        verify(rabbitTemplate).convertAndSend(eq(RabbitConfig.ORDER_EXCHANGE),
                eq(RabbitConfig.ROUTING_INVENTORY_RESERVED), any(InventoryReservedEvent.class));
        verify(channel).basicAck(1L, false);
    }

    @Test
    void hetHang_thiPublishInventoryFailed() throws IOException {
        when(inventoryRepository.tryReserve(eq("ORD-2"), anyList())).thenReturn(false);

        listener.handlePaymentSuccess(event("ORD-2"), channel, 2L);

        verify(rabbitTemplate).convertAndSend(eq(RabbitConfig.ORDER_EXCHANGE),
                eq(RabbitConfig.ROUTING_INVENTORY_FAILED), any(InventoryFailedEvent.class));
        verify(channel).basicAck(2L, false);
    }

    @Test
    void loiHeThong_thiNackKhongRequeue() throws IOException {
        when(inventoryRepository.tryReserve(anyString(), anyList()))
                .thenThrow(new RuntimeException("simulated system error"));

        listener.handlePaymentSuccess(event("ORD-3"), channel, 3L);

        verify(channel).basicNack(3L, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }
}
