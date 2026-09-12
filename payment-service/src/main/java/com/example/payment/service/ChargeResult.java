package com.example.payment.service;

/**
 * Kết quả của PaymentGateway.charge() - dùng enum thay vì boolean để phân biệt rõ
 * ALREADY_CHARGED (idempotent short-circuit do message bị RabbitMQ redeliver) với CHARGED
 * (lần đầu charge thật sự). PaymentListener dựa vào sự khác biệt này để quyết định có ghi
 * thêm 1 dòng vào ledger hay không - nếu không phân biệt, message bị redeliver sẽ tạo ra
 * 2 dòng CHARGE_SUCCESS trùng nhau trong ledger cho cùng 1 order.
 */
public enum ChargeResult {
    CHARGED,
    ALREADY_CHARGED,
    INSUFFICIENT_BALANCE
}
