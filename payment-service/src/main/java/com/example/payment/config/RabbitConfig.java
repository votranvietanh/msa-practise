package com.example.payment.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Khai báo hạ tầng RabbitMQ dùng bởi Payment Service.
 *
 * Ghi chú cho junior mới làm quen RabbitMQ:
 *  - Exchange: nơi message được publish vào đầu tiên. Bản thân exchange KHÔNG lưu message,
 *    nó chỉ định tuyến (route) message tới (các) queue theo routing key.
 *  - TopicExchange: loại exchange cho phép route theo pattern (vd "order.*"), ở demo này
 *    dùng đúng routing key tuyệt đối (vd "order.created") nên hoạt động như route chính xác.
 *  - Queue: nơi message thực sự được lưu lại chờ consumer xử lý.
 *  - Binding: "sợi dây" nối 1 exchange với 1 queue, kèm điều kiện routing key nào thì
 *    message được chuyển vào queue đó.
 */
@Configuration
public class RabbitConfig {

    public static final String ORDER_EXCHANGE = "order.topic.exchange";
    public static final String ROUTING_PAYMENT_SUCCESS = "payment.success";
    public static final String ROUTING_PAYMENT_FAILED = "payment.failed";
    public static final String ROUTING_PAYMENT_REFUNDED = "payment.refunded";

    /** Tên Dead Letter Exchange dùng chung cho các queue "chết" của Payment Service */
    private static final String DEAD_LETTER_EXCHANGE = "order.dlx";

    @Bean
    public TopicExchange orderExchange() {
        return new TopicExchange(ORDER_EXCHANGE, true, false);
    }

    /** Queue nhận event "order.created" từ Order Service */
    @Bean
    public Queue paymentQueue() {
        return QueueBuilder.durable("payment.queue")
                // Nếu message bị basicNack(requeue=false) hoặc hết TTL, RabbitMQ sẽ tự động
                // "chuyển hộ" nó sang exchange này với routing key bên dưới, thay vì xoá luôn.
                .withArgument("x-dead-letter-exchange", DEAD_LETTER_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", "payment.dead")
                .build();
    }

    @Bean
    public Binding paymentBinding() {
        return BindingBuilder.bind(paymentQueue()).to(orderExchange()).with("order.created");
    }

    /** Queue riêng nhận yêu cầu hoàn tiền (compensate) khi Inventory Service báo hết hàng */
    @Bean
    public Queue refundQueue() {
        return QueueBuilder.durable("payment.refund.queue")
                .withArgument("x-dead-letter-exchange", DEAD_LETTER_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", "refund.dead")
                .build();
    }

    @Bean
    public Binding refundBinding() {
        return BindingBuilder.bind(refundQueue()).to(orderExchange()).with("payment.refund");
    }

    /**
     * DEAD LETTER EXCHANGE (DLX): KHÔNG phải khai báo "x-dead-letter-exchange" trên queue
     * là xong — argument đó chỉ TRỎ TỚI tên 1 exchange, exchange đó vẫn phải được khai báo
     * và có queue lắng nghe thật thì message chết mới có chỗ để "rơi vào" và dev kiểm tra
     * lại được (nếu không, RabbitMQ sẽ âm thầm làm mất message chết vì exchange không tồn tại).
     * Dùng DirectExchange vì mỗi routing-key chết (vd "payment.dead") chỉ cần match ĐÚNG
     * 1 queue, không cần pattern như TopicExchange.
     */
    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange(DEAD_LETTER_EXCHANGE, true, false);
    }

    @Bean
    public Queue paymentDeadLetterQueue() {
        return QueueBuilder.durable("payment.queue.dlq").build();
    }

    @Bean
    public Binding paymentDeadLetterBinding() {
        return BindingBuilder.bind(paymentDeadLetterQueue()).to(deadLetterExchange()).with("payment.dead");
    }

    @Bean
    public Queue refundDeadLetterQueue() {
        return QueueBuilder.durable("payment.refund.queue.dlq").build();
    }

    @Bean
    public Binding refundDeadLetterBinding() {
        return BindingBuilder.bind(refundDeadLetterQueue()).to(deadLetterExchange()).with("refund.dead");
    }

    /** Cho Spring tự convert POJO (event class) <-> JSON khi publish/consume, khỏi tự viết parser */
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
