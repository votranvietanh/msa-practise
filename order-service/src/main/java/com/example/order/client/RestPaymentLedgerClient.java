package com.example.order.client;

import com.example.order.dto.PaymentTransactionDTO;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Implementation thật của PaymentLedgerClient, gọi HTTP GET /payments/ledger sang
 * Payment Service (xem RestClientConfig để biết baseUrl lấy từ đâu).
 */
@Component
public class RestPaymentLedgerClient implements PaymentLedgerClient {

    private final RestClient paymentServiceRestClient;

    public RestPaymentLedgerClient(RestClient paymentServiceRestClient) {
        this.paymentServiceRestClient = paymentServiceRestClient;
    }

    @Override
    public List<PaymentTransactionDTO> fetchLedger() {
        PaymentTransactionDTO[] transactions = paymentServiceRestClient.get()
                .uri("/payments/ledger")
                .retrieve()
                .body(PaymentTransactionDTO[].class);

        return transactions == null ? List.of() : List.of(transactions);
    }
}
