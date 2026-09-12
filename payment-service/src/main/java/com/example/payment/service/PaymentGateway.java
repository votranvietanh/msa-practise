package com.example.payment.service;

/**
 * Interface (không phải class cụ thể) để PaymentListener/RefundListener phụ thuộc vào
 * "hợp đồng" trừu tượng thay vì phụ thuộc thẳng vào cách gọi cổng thanh toán thật sự bên
 * dưới (Dependency Inversion Principle) - dễ mock khi unit test, dễ thay bằng 1 implementation
 * gọi cổng thanh toán thật (Stripe/VNPay/Momo...) sau này mà không phải sửa listener.
 */
public interface PaymentGateway {

    ChargeResult charge(String orderId, String userId, long amount);

    RefundResult refund(String orderId, String userId, long amount);

    long getBalance(String userId);
}
