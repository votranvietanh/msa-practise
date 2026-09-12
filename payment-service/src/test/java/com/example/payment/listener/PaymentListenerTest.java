package com.example.payment.listener;

import com.example.payment.config.RabbitConfig;
import com.example.payment.event.OrderCreatedEvent;
import com.example.payment.event.PaymentFailedEvent;
import com.example.payment.event.PaymentSuccessEvent;
import com.example.payment.ledger.PaymentLedger;
import com.example.payment.ledger.TransactionType;
import com.example.payment.service.ChargeResult;
import com.example.payment.service.PaymentGateway;
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

/**
 * Mock PaymentGateway/PaymentLedger/RabbitTemplate - test này chỉ verify PaymentListener
 * ĐIỀU PHỐI đúng (charge() trả gì thì publish event nào, ghi ledger khi nào), không verify
 * lại logic charge tiền (đã có InMemoryPaymentGatewayTest lo việc đó).
 */
@ExtendWith(MockitoExtension.class)
class PaymentListenerTest {

    @Mock private RabbitTemplate rabbitTemplate;
    @Mock private PaymentGateway paymentGateway;
    @Mock private PaymentLedger paymentLedger;
    @Mock private Channel channel;

    private PaymentListener listener;

    @BeforeEach
    void setUp() {
        listener = new PaymentListener(rabbitTemplate, paymentGateway, paymentLedger);
    }

    private OrderCreatedEvent event(String orderId, String userId, long amount) {
        OrderCreatedEvent event = new OrderCreatedEvent();
        event.setOrderId(orderId);
        event.setUserId(userId);
        event.setAmount(amount);
        event.setItems(List.of());
        return event;
    }

    @Test
    void charge_thanhCong_publishSuccessVaGhiLedger() throws IOException {
        when(paymentGateway.charge("ORD-1", "U001", 250_000L)).thenReturn(ChargeResult.CHARGED);

        listener.handleOrderCreated(event("ORD-1", "U001", 250_000L), channel, 1L);

        verify(paymentLedger).record(argThat(tx ->
                tx.getType() == TransactionType.CHARGE_SUCCESS && tx.getOrderId().equals("ORD-1")));
        verify(rabbitTemplate).convertAndSend(eq(RabbitConfig.ORDER_EXCHANGE),
                eq(RabbitConfig.ROUTING_PAYMENT_SUCCESS), any(PaymentSuccessEvent.class));
        verify(channel).basicAck(1L, false);
    }

    @Test
    void daChargeTruocDo_publishLaiSuccess_khongGhiLedgerTrung() throws IOException {
        when(paymentGateway.charge("ORD-2", "U001", 250_000L)).thenReturn(ChargeResult.ALREADY_CHARGED);

        listener.handleOrderCreated(event("ORD-2", "U001", 250_000L), channel, 2L);

        // Idempotent: message bị redeliver -> vẫn báo Saga tiếp tục (publish success),
        // nhưng KHÔNG được ghi thêm 1 dòng ledger nữa cho cùng 1 order.
        verify(paymentLedger, never()).record(any());
        verify(rabbitTemplate).convertAndSend(eq(RabbitConfig.ORDER_EXCHANGE),
                eq(RabbitConfig.ROUTING_PAYMENT_SUCCESS), any(PaymentSuccessEvent.class));
        verify(channel).basicAck(2L, false);
    }

    @Test
    void khongDuTien_publishFailed_ghiLedgerFailed() throws IOException {
        when(paymentGateway.charge("ORD-3", "U002", 250_000L)).thenReturn(ChargeResult.INSUFFICIENT_BALANCE);

        listener.handleOrderCreated(event("ORD-3", "U002", 250_000L), channel, 3L);

        verify(paymentLedger).record(argThat(tx -> tx.getType() == TransactionType.CHARGE_FAILED));
        verify(rabbitTemplate).convertAndSend(eq(RabbitConfig.ORDER_EXCHANGE),
                eq(RabbitConfig.ROUTING_PAYMENT_FAILED), any(PaymentFailedEvent.class));
        verify(channel).basicAck(3L, false);
    }

    @Test
    void loiHeThong_thiNackKhongRequeue_deChuyenSangDLQ() throws IOException {
        when(paymentGateway.charge(anyString(), anyString(), anyLong()))
                .thenThrow(new RuntimeException("simulated system error"));

        listener.handleOrderCreated(event("ORD-4", "U001", 250_000L), channel, 4L);

        verify(channel).basicNack(4L, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verify(paymentLedger, never()).record(any());
    }
}
