package com.example.inventory.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    public static final String ORDER_EXCHANGE = "order.topic.exchange";
    public static final String ROUTING_INVENTORY_RESERVED = "inventory.reserved";
    public static final String ROUTING_INVENTORY_FAILED = "inventory.failed";

    /** Tên Dead Letter Exchange dùng chung cho các queue "chết" của Inventory Service */
    private static final String DEAD_LETTER_EXCHANGE = "order.dlx";

    @Bean
    public TopicExchange orderExchange() {
        return new TopicExchange(ORDER_EXCHANGE, true, false);
    }

    /** Queue nhận event "payment.success" từ Payment Service */
    @Bean
    public Queue inventoryQueue() {
        return QueueBuilder.durable("inventory.queue")
                .withArgument("x-dead-letter-exchange", DEAD_LETTER_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", "inventory.dead")
                .build();
    }

    @Bean
    public Binding inventoryBinding() {
        return BindingBuilder.bind(inventoryQueue()).to(orderExchange()).with("payment.success");
    }

    /**
     * Exchange + queue thật để hứng message bị basicNack(requeue=false) từ "inventory.queue"
     * (xem comment chi tiết về lý do cần khai báo exchange này trong payment-service's RabbitConfig).
     */
    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange(DEAD_LETTER_EXCHANGE, true, false);
    }

    @Bean
    public Queue inventoryDeadLetterQueue() {
        return QueueBuilder.durable("inventory.queue.dlq").build();
    }

    @Bean
    public Binding inventoryDeadLetterBinding() {
        return BindingBuilder.bind(inventoryDeadLetterQueue()).to(deadLetterExchange()).with("inventory.dead");
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
