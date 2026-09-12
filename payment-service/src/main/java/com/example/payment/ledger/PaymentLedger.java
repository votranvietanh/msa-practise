package com.example.payment.ledger;

import java.util.List;

/**
 * Interface tách khỏi cách lưu trữ thật (Dependency Inversion, giống PaymentGateway) -
 * dễ mock khi test PaymentListener/RefundListener, dễ đổi sang lưu DB thật sau này.
 */
public interface PaymentLedger {

    void record(PaymentTransaction transaction);

    List<PaymentTransaction> findAll();

    List<PaymentTransaction> findByOrderId(String orderId);
}
