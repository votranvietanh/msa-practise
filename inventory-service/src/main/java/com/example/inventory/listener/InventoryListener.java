package com.example.inventory.listener;

import com.example.inventory.config.RabbitConfig;
import com.example.inventory.event.InventoryFailedEvent;
import com.example.inventory.event.InventoryReservedEvent;
import com.example.inventory.event.PaymentSuccessEvent;
import com.example.inventory.repository.InventoryRepository;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * INPUT (từ Payment Service qua "inventory.queue", routing key "payment.success"):
 * {
 *   "orderId": "ORD-1001", "userId": "U001", "amount": 250000,
 *   "items": [{"sku":"ITEM-01","qty":2}]
 * }
 *
 * OUTPUT (còn hàng, routing key "inventory.reserved"):
 * { "orderId": "ORD-1001" }
 *
 * OUTPUT (hết hàng, routing key "inventory.failed"):
 * { "orderId": "ORD-1001", "userId": "U001", "amount": 250000, "reason": "OUT_OF_STOCK" }
 */
@Component
public class InventoryListener {

    private final RabbitTemplate rabbitTemplate;
    private final InventoryRepository inventoryRepository;

    public InventoryListener(RabbitTemplate rabbitTemplate, InventoryRepository inventoryRepository) {
        this.rabbitTemplate = rabbitTemplate;
        this.inventoryRepository = inventoryRepository;
    }

    /**
     * Cùng cơ chế manual ack/nack như PaymentListener.handleOrderCreated (xem comment ở đó
     * để hiểu vì sao KHÔNG dùng auto-ack, và vì sao nack dùng requeue=false).
     */
    @RabbitListener(queues = "inventory.queue")
    public void handlePaymentSuccess(PaymentSuccessEvent event,
                                      Channel channel,
                                      @Header(AmqpHeaders.DELIVERY_TAG) long tag) throws IOException {
        try {
            System.out.println("[InventoryListener] Received payment.success for order " + event.getOrderId());

            boolean reserved = inventoryRepository.tryReserve(event.getOrderId(), event.getItems());

            if (reserved) {
                rabbitTemplate.convertAndSend(
                        RabbitConfig.ORDER_EXCHANGE,
                        RabbitConfig.ROUTING_INVENTORY_RESERVED,
                        new InventoryReservedEvent(event.getOrderId()));

                System.out.println("[InventoryListener] Reserved stock OK -> published inventory.reserved for "
                        + event.getOrderId());
            } else {
                rabbitTemplate.convertAndSend(
                        RabbitConfig.ORDER_EXCHANGE,
                        RabbitConfig.ROUTING_INVENTORY_FAILED,
                        new InventoryFailedEvent(event.getOrderId(), event.getUserId(), event.getAmount(), "OUT_OF_STOCK"));

                System.out.println("[InventoryListener] OUT_OF_STOCK -> published inventory.failed for "
                        + event.getOrderId() + " (Order Service sẽ trigger refund)");
            }

            channel.basicAck(tag, false);

        } catch (Exception e) {
            channel.basicNack(tag, false, false);
        }
    }
}
