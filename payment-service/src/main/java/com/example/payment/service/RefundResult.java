package com.example.payment.service;

/** Kết quả của PaymentGateway.refund() - xem giải thích tương tự ở ChargeResult.java */
public enum RefundResult {
    REFUNDED,
    ALREADY_REFUNDED
}
