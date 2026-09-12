package com.example.payment.listener;

import com.example.payment.config.RabbitConfig;
import com.example.payment.event.OrderCreatedEvent;
import com.example.payment.event.PaymentFailedEvent;
import com.example.payment.event.PaymentSuccessEvent;
import com.example.payment.ledger.PaymentLedger;
import com.example.payment.ledger.PaymentTransaction;
import com.example.payment.ledger.TransactionType;
import com.example.payment.service.ChargeResult;
import com.example.payment.service.PaymentGateway;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * INPUT (từ Order Service qua "payment.queue", routing key "order.created"):
 * {
 *   "orderId": "ORD-1001",
 *   "userId": "U001",
 *   "amount": 250000,
 *   "items": [{"sku":"ITEM-01","qty":2}]
 * }
 *
 * OUTPUT (trường hợp thành công, routing key "payment.success"):
 * { "orderId": "ORD-1001", "userId": "U001", "amount": 250000, "items": [...] }
 *
 * OUTPUT (trường hợp thất bại, routing key "payment.failed"):
 * { "orderId": "ORD-1001", "reason": "INSUFFICIENT_BALANCE" }
 */
@Component
public class PaymentListener {

    private final RabbitTemplate rabbitTemplate;
    private final PaymentGateway paymentGateway;
    private final PaymentLedger paymentLedger;

    public PaymentListener(RabbitTemplate rabbitTemplate, PaymentGateway paymentGateway, PaymentLedger paymentLedger) {
        this.rabbitTemplate = rabbitTemplate;
        this.paymentGateway = paymentGateway;
        this.paymentLedger = paymentLedger;
    }

    /**
     * @RabbitListener(queues = "payment.queue"): Spring AMQP tự tạo 1 consumer chạy nền,
     * lắng nghe queue "payment.queue" (khai báo ở RabbitConfig) và gọi method này mỗi khi
     * có message mới. Message JSON được tự động convert thành OrderCreatedEvent nhờ bean
     * Jackson2JsonMessageConverter (khai báo ở RabbitConfig) - không cần tự parse JSON.
     *
     * Channel + @Header(DELIVERY_TAG): dùng để tự tay ACK/NACK message (xem giải thích
     * chi tiết trong khối try/catch bên dưới) thay vì để Spring auto-ack ngay khi nhận được.
     * Auto-ack rất nguy hiểm cho luồng nghiệp vụ: nếu service crash NGAY SAU khi ack nhưng
     * TRƯỚC KHI xử lý xong, message sẽ mất vĩnh viễn dù chưa charge tiền thành công.
     */
    @RabbitListener(queues = "payment.queue")
    public void handleOrderCreated(OrderCreatedEvent event,
                                    Channel channel,
                                    @Header(AmqpHeaders.DELIVERY_TAG) long tag) throws IOException {
        try {
            System.out.println("[PaymentListener] Received order " + event.getOrderId()
                    + ", charging " + event.getAmount() + " for " + event.getUserId());

            ChargeResult result = paymentGateway.charge(event.getOrderId(), event.getUserId(), event.getAmount());

            switch (result) {
                case CHARGED -> {
                    // Chỉ ghi ledger + publish khi đây THỰC SỰ là lần charge đầu tiên.
                    // Nếu ghi ledger cả ở nhánh ALREADY_CHARGED, message bị redeliver sẽ
                    // tạo ra 2 dòng CHARGE_SUCCESS trùng nhau cho cùng 1 order.
                    paymentLedger.record(new PaymentTransaction(event.getOrderId(), event.getUserId(),
                            event.getAmount(), TransactionType.CHARGE_SUCCESS, Instant.now()));
                    publishSuccess(event);
                    System.out.println("[PaymentListener] Charge OK -> published payment.success for "
                            + event.getOrderId());
                }
                case ALREADY_CHARGED -> {
                    // Idempotent short-circuit: message này đã được xử lý ở lần nhận trước
                    // (redeliver do consumer crash trước khi kịp ack) - vẫn publish lại
                    // payment.success để Saga tiếp tục đúng flow, nhưng KHÔNG charge/ghi
                    // ledger thêm lần nữa.
                    publishSuccess(event);
                    System.out.println("[PaymentListener] Order " + event.getOrderId()
                            + " đã charge trước đó (idempotent) -> publish lại payment.success, không charge lại");
                }
                case INSUFFICIENT_BALANCE -> {
                    paymentLedger.record(new PaymentTransaction(event.getOrderId(), event.getUserId(),
                            event.getAmount(), TransactionType.CHARGE_FAILED, Instant.now()));
                    rabbitTemplate.convertAndSend(
                            RabbitConfig.ORDER_EXCHANGE,
                            RabbitConfig.ROUTING_PAYMENT_FAILED,
                            new PaymentFailedEvent(event.getOrderId(), "INSUFFICIENT_BALANCE"));
                    System.out.println("[PaymentListener] Charge FAILED (insufficient balance) -> published payment.failed for "
                            + event.getOrderId());
                }
            }

            // basicAck(tag, multiple=false): xác nhận với RabbitMQ "message này đã xử lý xong,
            // có thể xoá khỏi queue". multiple=false nghĩa là CHỈ ack message có tag này,
            // không ack luôn các message trước đó trong cùng channel.
            channel.basicAck(tag, false);

        } catch (Exception e) {
            // Lỗi hệ thống thật sự (không phải business fail như hết tiền) -> đẩy sang DLQ.
            // basicNack(tag, multiple=false, requeue=false): từ chối message này, và
            // requeue=false nghĩa là KHÔNG trả nó về lại đầu queue để tránh vòng lặp
            // vô hạn (consumer nhận lại - lỗi tiếp - nhận lại...). Vì queue "payment.queue"
            // có khai báo x-dead-letter-exchange, message sẽ tự động được chuyển sang DLQ
            // để dev vào kiểm tra sau, thay vì mất luôn.
            channel.basicNack(tag, false, false);
        }
    }

    private void publishSuccess(OrderCreatedEvent event) {
        // Chuyển tiếp nguyên "items" nhận được từ order.created sang payment.success,
        // vì Inventory Service (consumer tiếp theo) cần items để biết trừ kho SKU nào.
        List<PaymentSuccessEvent.ItemDTO> items = event.getItems() == null
                ? List.of()
                : event.getItems().stream()
                        .map(i -> new PaymentSuccessEvent.ItemDTO(i.getSku(), i.getQty()))
                        .collect(Collectors.toList());

        rabbitTemplate.convertAndSend(
                RabbitConfig.ORDER_EXCHANGE,
                RabbitConfig.ROUTING_PAYMENT_SUCCESS,
                new PaymentSuccessEvent(event.getOrderId(), event.getUserId(), event.getAmount(), items));
    }
}
