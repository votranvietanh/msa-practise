package com.example.order.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * Bản định nghĩa (phía Order Service) của 1 dòng trong ledger giao dịch thanh toán mà
 * Payment Service trả về qua GET /payments/ledger (xem payment-service's PaymentTransaction).
 *
 * Đây KHÔNG phải 1 event trong luồng Saga (không qua RabbitMQ) mà là kết quả của 1 lời gọi
 * REST đồng bộ, CHỈ phục vụ tác vụ đối soát (reconciliation) - việc đọc dữ liệu, không phải
 * việc thay đổi trạng thái nghiệp vụ, nên chấp nhận Order Service "biết" tới Payment Service
 * qua HTTP ở đây là hợp lý (khác với luồng Saga chính vẫn phải giữ nguyên tắc chỉ giao tiếp
 * qua message, xem RestClientConfig).
 */
public class PaymentTransactionDTO {
    private final String orderId;
    private final String userId;
    private final long amount;
    private final String type; // CHARGE_SUCCESS | CHARGE_FAILED | REFUND
    private final Instant timestamp;

    @JsonCreator
    public PaymentTransactionDTO(@JsonProperty("orderId") String orderId,
                                  @JsonProperty("userId") String userId,
                                  @JsonProperty("amount") long amount,
                                  @JsonProperty("type") String type,
                                  @JsonProperty("timestamp") Instant timestamp) {
        this.orderId = orderId;
        this.userId = userId;
        this.amount = amount;
        this.type = type;
        this.timestamp = timestamp;
    }

    public String getOrderId() { return orderId; }
    public String getUserId() { return userId; }
    public long getAmount() { return amount; }
    public String getType() { return type; }
    public Instant getTimestamp() { return timestamp; }
}
