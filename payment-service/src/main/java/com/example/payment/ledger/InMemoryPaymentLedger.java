package com.example.payment.ledger;

import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * CopyOnWriteArrayList: an toàn khi nhiều thread consumer của Rabbit cùng ghi (record)
 * song song, phù hợp với ledger vì thao tác ĐỌC (findAll/findByOrderId cho reconciliation)
 * diễn ra thường xuyên hơn nhiều so với ghi.
 */
@Repository
public class InMemoryPaymentLedger implements PaymentLedger {

    private final List<PaymentTransaction> transactions = new CopyOnWriteArrayList<>();

    @Override
    public void record(PaymentTransaction transaction) {
        transactions.add(transaction);
    }

    @Override
    public List<PaymentTransaction> findAll() {
        return List.copyOf(transactions);
    }

    @Override
    public List<PaymentTransaction> findByOrderId(String orderId) {
        return transactions.stream()
                .filter(t -> t.getOrderId().equals(orderId))
                .toList();
    }
}
