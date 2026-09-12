package com.example.payment.controller;

import com.example.payment.ledger.PaymentLedger;
import com.example.payment.ledger.PaymentTransaction;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Expose ledger giao dịch cho tác vụ đối soát (reconciliation) mà Order Service gọi sang
 * (xem order-service's ReconciliationService/PaymentLedgerClient). Đây là API READ-ONLY,
 * không nằm trong luồng Saga chính.
 */
@RestController
@RequestMapping("/payments/ledger")
public class PaymentLedgerController {

    private final PaymentLedger paymentLedger;

    public PaymentLedgerController(PaymentLedger paymentLedger) {
        this.paymentLedger = paymentLedger;
    }

    @GetMapping
    public List<PaymentTransaction> all() {
        return paymentLedger.findAll();
    }

    @GetMapping("/{orderId}")
    public List<PaymentTransaction> byOrder(@PathVariable String orderId) {
        return paymentLedger.findByOrderId(orderId);
    }
}
