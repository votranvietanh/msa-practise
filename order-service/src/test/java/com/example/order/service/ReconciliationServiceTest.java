package com.example.order.service;

import com.example.order.client.PaymentLedgerClient;
import com.example.order.dto.PaymentTransactionDTO;
import com.example.order.dto.ReconciliationReport;
import com.example.order.entity.Order;
import com.example.order.entity.OrderStatus;
import com.example.order.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReconciliationServiceTest {

    @Mock private OrderRepository orderRepository;
    @Mock private PaymentLedgerClient paymentLedgerClient;

    private ReconciliationService newService(Duration stuckThreshold) {
        return new ReconciliationService(orderRepository, paymentLedgerClient, stuckThreshold);
    }

    private PaymentTransactionDTO txn(String orderId, String type) {
        return new PaymentTransactionDTO(orderId, "U001", 250_000L, type, Instant.now());
    }

    @Test
    void order_completed_maKhongCoChargeSuccess_thiBiFlagMissingCharge() {
        Order order = new Order("ORD-1", "U001", 250_000L, List.of(), OrderStatus.COMPLETED);
        when(orderRepository.findAll()).thenReturn(List.of(order));
        when(paymentLedgerClient.fetchLedger()).thenReturn(List.of()); // không có giao dịch nào bên Payment

        ReconciliationReport report = newService(Duration.ofMinutes(5)).reconcile();

        assertThat(report.getIssuesFound()).isEqualTo(1);
        assertThat(report.getIssues().get(0).getIssueType()).isEqualTo("MISSING_CHARGE");
    }

    @Test
    void order_completed_vaCoChargeSuccess_thiKhongCoIssue() {
        Order order = new Order("ORD-2", "U001", 250_000L, List.of(), OrderStatus.COMPLETED);
        when(orderRepository.findAll()).thenReturn(List.of(order));
        when(paymentLedgerClient.fetchLedger()).thenReturn(List.of(txn("ORD-2", "CHARGE_SUCCESS")));

        ReconciliationReport report = newService(Duration.ofMinutes(5)).reconcile();

        assertThat(report.getIssuesFound()).isZero();
    }

    @Test
    void order_failed_daChargeNhungChuaRefund_thiBiFlagChargedNotRefunded() {
        Order order = new Order("ORD-3", "U001", 250_000L, List.of(), OrderStatus.FAILED);
        when(orderRepository.findAll()).thenReturn(List.of(order));
        when(paymentLedgerClient.fetchLedger()).thenReturn(List.of(txn("ORD-3", "CHARGE_SUCCESS")));

        ReconciliationReport report = newService(Duration.ofMinutes(5)).reconcile();

        assertThat(report.getIssuesFound()).isEqualTo(1);
        assertThat(report.getIssues().get(0).getIssueType()).isEqualTo("CHARGED_NOT_REFUNDED");
    }

    @Test
    void order_failed_daChargeVaDaRefund_thiKhongCoIssue() {
        Order order = new Order("ORD-4", "U001", 250_000L, List.of(), OrderStatus.FAILED);
        when(orderRepository.findAll()).thenReturn(List.of(order));
        when(paymentLedgerClient.fetchLedger())
                .thenReturn(List.of(txn("ORD-4", "CHARGE_SUCCESS"), txn("ORD-4", "REFUND")));

        ReconciliationReport report = newService(Duration.ofMinutes(5)).reconcile();

        assertThat(report.getIssuesFound()).isZero();
    }

    @Test
    void order_pendingQuaLau_thiBiFlagStuckPending() throws InterruptedException {
        Order order = new Order("ORD-5", "U001", 100_000L, List.of(), OrderStatus.PENDING);
        Thread.sleep(5); // đảm bảo order "già" hơn threshold cực ngắn bên dưới

        when(orderRepository.findAll()).thenReturn(List.of(order));
        when(paymentLedgerClient.fetchLedger()).thenReturn(List.of());

        ReconciliationReport report = newService(Duration.ofMillis(1)).reconcile();

        assertThat(report.getIssuesFound()).isEqualTo(1);
        assertThat(report.getIssues().get(0).getIssueType()).isEqualTo("STUCK_PENDING");
    }

    @Test
    void order_pendingMoiTao_thiChuaBiFlag() {
        Order order = new Order("ORD-6", "U001", 100_000L, List.of(), OrderStatus.PENDING);
        when(orderRepository.findAll()).thenReturn(List.of(order));
        when(paymentLedgerClient.fetchLedger()).thenReturn(List.of());

        ReconciliationReport report = newService(Duration.ofMinutes(5)).reconcile();

        assertThat(report.getIssuesFound()).isZero();
    }
}
