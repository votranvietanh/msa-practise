package com.example.order.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Khai báo hạ tầng RabbitMQ dùng bởi Order Service:
 *   - Exchange chung "order.topic.exchange" (Topic Exchange, dùng chung cho cả 3 service)
 *   - Queue "order.status.queue": nơi Order Service NHẬN kết quả cuối từ Payment/Inventory
 *   - Queue "payment.refund.queue": KHÔNG khai báo ở đây vì đây là queue Payment Service consume,
 *     Order Service chỉ cần publish đúng routing key "payment.refund", không cần biết queue nào nhận.
 */
@Configuration
public class RabbitConfig {

    public static final String ORDER_EXCHANGE = "order.topic.exchange";

    public static final String ROUTING_ORDER_CREATED = "order.created";
    public static final String ROUTING_PAYMENT_REFUND = "payment.refund";

    /** Tên Dead Letter Exchange dùng chung cho các queue "chết" của Order Service */
    private static final String DEAD_LETTER_EXCHANGE = "order.dlx";

    @Bean
    public TopicExchange orderExchange() {
        return new TopicExchange(ORDER_EXCHANGE, true, false); // durable = true
    }

    /** Queue này gom TẤT CẢ event kết quả cuối để Order Service cập nhật status */
    @Bean
    public Queue orderStatusQueue() {
        return QueueBuilder.durable("order.status.queue")
                .withArgument("x-dead-letter-exchange", DEAD_LETTER_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", "order.status.dead")
                .build();
    }

    /**
     * "order.status.queue" nhận NHIỀU routing key khác nhau (mọi kết quả cuối của Saga)
     * nên cần khai báo 1 Binding riêng cho từng routing key. payment.success KHÔNG làm
     * order COMPLETED ngay (chỉ log tiến độ) — phải chờ tiếp inventory.reserved mới
     * coi là xong toàn bộ Saga (xem OrderStatusListener).
     */
    @Bean
    public Binding bindPaymentSuccess() {
        return BindingBuilder.bind(orderStatusQueue()).to(orderExchange()).with("payment.success");
    }

    @Bean
    public Binding bindPaymentFailed() {
        return BindingBuilder.bind(orderStatusQueue()).to(orderExchange()).with("payment.failed");
    }

    @Bean
    public Binding bindInventoryReserved() {
        return BindingBuilder.bind(orderStatusQueue()).to(orderExchange()).with("inventory.reserved");
    }

    @Bean
    public Binding bindInventoryFailed() {
        return BindingBuilder.bind(orderStatusQueue()).to(orderExchange()).with("inventory.failed");
    }

    /**
     * payment.refunded: Payment Service publish sau khi hoàn tiền xong (bước compensate
     * cuối cùng của Saga). Order Service không cần đổi status (đã là FAILED từ bước
     * inventory.failed rồi) nhưng vẫn cần BIND + lắng nghe để có audit log xác nhận
     * "tiền đã thực sự được hoàn" — thiếu binding này thì message payment.refunded sẽ
     * không khớp queue nào, bị Topic Exchange "rơi mất" trong im lặng (không lỗi, không
     * log, rất khó phát hiện khi debug).
     */
    @Bean
    public Binding bindPaymentRefunded() {
        return BindingBuilder.bind(orderStatusQueue()).to(orderExchange()).with("payment.refunded");
    }

    /**
     * Exchange + queue thật để hứng message bị basicNack(requeue=false) từ "order.status.queue"
     * (xem comment chi tiết về lý do cần khai báo exchange này trong payment-service's RabbitConfig).
     */
    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange(DEAD_LETTER_EXCHANGE, true, false);
    }

    @Bean
    public Queue orderStatusDeadLetterQueue() {
        return QueueBuilder.durable("order.status.queue.dlq").build();
    }

    @Bean
    public Binding orderStatusDeadLetterBinding() {
        return BindingBuilder.bind(orderStatusDeadLetterQueue()).to(deadLetterExchange()).with("order.status.dead");
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
