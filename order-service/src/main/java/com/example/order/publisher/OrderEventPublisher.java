package com.example.order.publisher;

import com.example.order.config.RabbitConfig;
import com.example.order.event.OrderCreatedEvent;
import com.example.order.event.RefundRequestEvent;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Tầng chịu trách nhiệm publish message ra RabbitMQ.
 * Tách riêng khỏi OrderService để service layer không cần biết chi tiết cách publish.
 */
@Component
public class OrderEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    public OrderEventPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * rabbitTemplate.convertAndSend(exchange, routingKey, event, messagePostProcessor):
     *  - Spring tự convert "event" (POJO) thành JSON rồi gửi tới "exchange" kèm "routingKey".
     *  - messagePostProcessor (lambda cuối) là hook để chỉnh message TRƯỚC khi gửi đi.
     *    Ở đây dùng để set deliveryMode = PERSISTENT: yêu cầu RabbitMQ GHI message này
     *    xuống đĩa, không chỉ giữ trong RAM — nếu broker bị restart giữa chừng, message
     *    chưa kịp xử lý vẫn còn đó thay vì mất trắng. Nếu bỏ dòng này (mặc định là
     *    NON_PERSISTENT), demo trông vẫn chạy bình thường vì không ai restart broker,
     *    nhưng lên production sẽ mất message một cách khó phát hiện.
     */
    public void publishOrderCreated(OrderCreatedEvent event) {
        rabbitTemplate.convertAndSend(
                RabbitConfig.ORDER_EXCHANGE,
                RabbitConfig.ROUTING_ORDER_CREATED,
                event,
                message -> {
                    message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                    return message;
                });
    }

    /** Compensating action: publish yêu cầu hoàn tiền khi Inventory Service báo hết hàng */
    public void publishRefundRequest(RefundRequestEvent event) {
        rabbitTemplate.convertAndSend(
                RabbitConfig.ORDER_EXCHANGE,
                RabbitConfig.ROUTING_PAYMENT_REFUND,
                event);
    }
}
