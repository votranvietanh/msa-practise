package com.example.payment.listener;

import com.example.payment.config.RabbitConfig;
import com.example.payment.event.RefundCompletedEvent;
import com.example.payment.event.RefundRequestEvent;
import com.example.payment.ledger.PaymentLedger;
import com.example.payment.ledger.PaymentTransaction;
import com.example.payment.ledger.TransactionType;
import com.example.payment.service.PaymentGateway;
import com.example.payment.service.RefundResult;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;

/**
 * COMPENSATING ACTION của Saga: khi Inventory Service báo hết hàng,
 * Order Service publish "payment.refund" -> Payment Service lắng nghe ở đây để hoàn tiền.
 *
 * INPUT (routing key "payment.refund"):
 * { "orderId": "ORD-1001", "userId": "U001", "amount": 250000 }
 *
 * OUTPUT (routing key "payment.refunded"):
 * { "orderId": "ORD-1001" }
 */
@Component
public class RefundListener {

    private final RabbitTemplate rabbitTemplate;
    private final PaymentGateway paymentGateway;
    private final PaymentLedger paymentLedger;

    public RefundListener(RabbitTemplate rabbitTemplate, PaymentGateway paymentGateway, PaymentLedger paymentLedger) {
        this.rabbitTemplate = rabbitTemplate;
        this.paymentGateway = paymentGateway;
        this.paymentLedger = paymentLedger;
    }

    @RabbitListener(queues = "payment.refund.queue")
    public void handleRefund(RefundRequestEvent event,
                              Channel channel,
                              @Header(AmqpHeaders.DELIVERY_TAG) long tag) throws IOException {
        try {
            RefundResult result = paymentGateway.refund(event.getOrderId(), event.getUserId(), event.getAmount());

            if (result == RefundResult.REFUNDED) {
                // Chỉ ghi ledger khi đây thực sự là lần hoàn tiền đầu tiên (xem comment
                // tương tự trong PaymentListener về lý do không ghi ở nhánh idempotent).
                paymentLedger.record(new PaymentTransaction(event.getOrderId(), event.getUserId(),
                        event.getAmount(), TransactionType.REFUND, Instant.now()));
                System.out.println("[RefundListener] Refunded " + event.getAmount()
                        + " to " + event.getUserId() + " for order " + event.getOrderId());
            } else {
                System.out.println("[RefundListener] Order " + event.getOrderId()
                        + " đã hoàn tiền trước đó (idempotent) -> không hoàn lại lần nữa");
            }

            rabbitTemplate.convertAndSend(
                    RabbitConfig.ORDER_EXCHANGE,
                    RabbitConfig.ROUTING_PAYMENT_REFUNDED,
                    new RefundCompletedEvent(event.getOrderId()));

            // Xem comment chi tiết về basicAck/basicNack trong PaymentListener.handleOrderCreated
            channel.basicAck(tag, false);

        } catch (Exception e) {
            channel.basicNack(tag, false, false);
        }
    }
}
