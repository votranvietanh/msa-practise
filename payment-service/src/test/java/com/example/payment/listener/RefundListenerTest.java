package com.example.payment.listener;

import com.example.payment.config.RabbitConfig;
import com.example.payment.event.RefundCompletedEvent;
import com.example.payment.event.RefundRequestEvent;
import com.example.payment.ledger.PaymentLedger;
import com.example.payment.ledger.TransactionType;
import com.example.payment.service.PaymentGateway;
import com.example.payment.service.RefundResult;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.io.IOException;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RefundListenerTest {

    @Mock private RabbitTemplate rabbitTemplate;
    @Mock private PaymentGateway paymentGateway;
    @Mock private PaymentLedger paymentLedger;
    @Mock private Channel channel;

    private RefundListener listener;

    @BeforeEach
    void setUp() {
        listener = new RefundListener(rabbitTemplate, paymentGateway, paymentLedger);
    }

    @Test
    void refund_thanhCong_ghiLedgerVaPublishRefunded() throws IOException {
        RefundRequestEvent event = new RefundRequestEvent();
        event.setOrderId("ORD-1");
        event.setUserId("U001");
        event.setAmount(250_000L);
        when(paymentGateway.refund("ORD-1", "U001", 250_000L)).thenReturn(RefundResult.REFUNDED);

        listener.handleRefund(event, channel, 1L);

        verify(paymentLedger).record(argThat(tx -> tx.getType() == TransactionType.REFUND));
        verify(rabbitTemplate).convertAndSend(eq(RabbitConfig.ORDER_EXCHANGE),
                eq(RabbitConfig.ROUTING_PAYMENT_REFUNDED), any(RefundCompletedEvent.class));
        verify(channel).basicAck(1L, false);
    }

    @Test
    void daHoanTienTruocDo_khongGhiLedgerTrung_nhungVanPublishRefunded() throws IOException {
        RefundRequestEvent event = new RefundRequestEvent();
        event.setOrderId("ORD-2");
        event.setUserId("U001");
        event.setAmount(250_000L);
        when(paymentGateway.refund("ORD-2", "U001", 250_000L)).thenReturn(RefundResult.ALREADY_REFUNDED);

        listener.handleRefund(event, channel, 2L);

        verify(paymentLedger, never()).record(any());
        verify(rabbitTemplate).convertAndSend(eq(RabbitConfig.ORDER_EXCHANGE),
                eq(RabbitConfig.ROUTING_PAYMENT_REFUNDED), any(RefundCompletedEvent.class));
        verify(channel).basicAck(2L, false);
    }

    @Test
    void loiHeThong_thiNackKhongRequeue() throws IOException {
        RefundRequestEvent event = new RefundRequestEvent();
        event.setOrderId("ORD-3");
        event.setUserId("U001");
        event.setAmount(250_000L);
        when(paymentGateway.refund(anyString(), anyString(), anyLong()))
                .thenThrow(new RuntimeException("simulated system error"));

        listener.handleRefund(event, channel, 3L);

        verify(channel).basicNack(3L, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }
}
