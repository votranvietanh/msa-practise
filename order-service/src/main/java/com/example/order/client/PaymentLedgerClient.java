package com.example.order.client;

import com.example.order.dto.PaymentTransactionDTO;

import java.util.List;

/**
 * Tách riêng "lấy dữ liệu ledger từ Payment Service" thành 1 interface, KHÔNG cho
 * ReconciliationService phụ thuộc thẳng vào RestClient (Dependency Inversion Principle,
 * giống lý do tách OrderRepository thành interface).
 *
 * Lợi ích cụ thể ở đây: unit test ReconciliationService bằng cách mock interface này,
 * không cần dựng 1 HTTP server giả (MockRestServiceServer/WireMock) chỉ để test logic
 * so sánh dữ liệu - vốn là phần thực sự cần test kỹ.
 */
public interface PaymentLedgerClient {
    List<PaymentTransactionDTO> fetchLedger();
}
