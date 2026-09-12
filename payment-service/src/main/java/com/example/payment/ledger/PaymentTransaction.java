package com.example.payment.ledger;

import java.time.Instant;

/**
 * 1 dòng "sổ cái" ghi lại mỗi lần tiền thực sự di chuyển (hoặc cố di chuyển nhưng fail).
 * Đây là nguồn dữ liệu mà Order Service gọi sang (qua GET /payments/ledger) để ĐỐI SOÁT
 * (reconciliation) - so sánh "Order Service nghĩ là đã charge/refund" với "Payment Service
 * thực sự đã charge/refund" chưa.
 */
public class PaymentTransaction {
    private final String orderId;
    private final String userId;
    private final long amount;
    private final TransactionType type;
    private final Instant timestamp;

    public PaymentTransaction(String orderId, String userId, long amount, TransactionType type, Instant timestamp) {
        this.orderId = orderId;
        this.userId = userId;
        this.amount = amount;
        this.type = type;
        this.timestamp = timestamp;
    }

    public String getOrderId() { return orderId; }
    public String getUserId() { return userId; }
    public long getAmount() { return amount; }
    public TransactionType getType() { return type; }
    public Instant getTimestamp() { return timestamp; }
}
