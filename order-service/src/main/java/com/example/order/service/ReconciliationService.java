package com.example.order.service;

import com.example.order.client.PaymentLedgerClient;
import com.example.order.dto.PaymentTransactionDTO;
import com.example.order.dto.ReconciliationIssue;
import com.example.order.dto.ReconciliationReport;
import com.example.order.entity.Order;
import com.example.order.repository.OrderRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Đối soát (reconciliation): so sánh dữ liệu Order Service đang giữ với ledger giao dịch
 * thật sự bên Payment Service, để phát hiện những trường hợp 2 bên bị LỆCH NHAU - kiểu lỗi
 * mà luồng nghiệp vụ bình thường không tự phát hiện ra (vd 1 message bị rớt dọc đường khiến
 * Order Service tưởng đã xong nhưng Payment Service lại không có bản ghi tương ứng).
 *
 * Đây là tác vụ ĐỌC-ONLY, chạy độc lập với luồng Saga chính (không publish/consume event
 * nào cả) - phù hợp gọi định kỳ qua 1 scheduled job hoặc do admin gọi tay khi nghi ngờ có
 * vấn đề, không phải 1 bước bắt buộc của mỗi order.
 */
@Service
public class ReconciliationService {

    private static final String CHARGE_SUCCESS = "CHARGE_SUCCESS";
    private static final String REFUND = "REFUND";

    private final OrderRepository orderRepository;
    private final PaymentLedgerClient paymentLedgerClient;
    private final Duration stuckPendingThreshold;

    public ReconciliationService(OrderRepository orderRepository,
                                  PaymentLedgerClient paymentLedgerClient,
                                  @Value("${reconciliation.stuck-pending-threshold:PT5M}") Duration stuckPendingThreshold) {
        this.orderRepository = orderRepository;
        this.paymentLedgerClient = paymentLedgerClient;
        this.stuckPendingThreshold = stuckPendingThreshold;
    }

    public ReconciliationReport reconcile() {
        List<Order> orders = orderRepository.findAll();
        List<PaymentTransactionDTO> ledger = paymentLedgerClient.fetchLedger();

        Map<String, List<PaymentTransactionDTO>> ledgerByOrderId = ledger.stream()
                .collect(Collectors.groupingBy(PaymentTransactionDTO::getOrderId));

        List<ReconciliationIssue> issues = new ArrayList<>();
        for (Order order : orders) {
            checkOrder(order, ledgerByOrderId.getOrDefault(order.getId(), List.of()), issues);
        }

        return new ReconciliationReport(orders.size(), issues.size(), issues);
    }

    private void checkOrder(Order order, List<PaymentTransactionDTO> orderTransactions,
                             List<ReconciliationIssue> issues) {
        boolean hasChargeSuccess = orderTransactions.stream()
                .anyMatch(t -> CHARGE_SUCCESS.equals(t.getType()));
        boolean hasRefund = orderTransactions.stream()
                .anyMatch(t -> REFUND.equals(t.getType()));

        switch (order.getStatus()) {
            case COMPLETED -> {
                // Saga chỉ COMPLETED sau khi Payment charge thành công (payment.success) rồi
                // Inventory mới reserve được kho - nếu không thấy giao dịch CHARGE_SUCCESS nào
                // bên Payment Service thì 2 hệ thống đang lệch nhau nghiêm trọng.
                if (!hasChargeSuccess) {
                    issues.add(new ReconciliationIssue(order.getId(), "MISSING_CHARGE",
                            "Order COMPLETED nhưng không tìm thấy giao dịch CHARGE_SUCCESS tương ứng bên Payment Service"));
                }
            }
            case FAILED -> {
                // Order FAILED do hết hàng (inventory.failed) thì Saga đã compensate bằng
                // cách hoàn tiền - nếu có charge thành công mà KHÔNG có refund, nhiều khả năng
                // user đã bị trừ tiền nhưng chưa được hoàn (rất đáng báo động về mặt tài chính).
                if (hasChargeSuccess && !hasRefund) {
                    issues.add(new ReconciliationIssue(order.getId(), "CHARGED_NOT_REFUNDED",
                            "Order FAILED, tiền đã charge thành công nhưng CHƯA có giao dịch REFUND - user có thể bị mất tiền oan"));
                }
            }
            case PENDING -> {
                // Order đứng PENDING quá lâu nghĩa là Saga có thể đã "kẹt" giữa chừng
                // (message bị mất, 1 service đang down, DLQ có message chưa ai xử lý...).
                Duration age = Duration.between(order.getCreatedAt(), Instant.now());
                if (age.compareTo(stuckPendingThreshold) > 0) {
                    issues.add(new ReconciliationIssue(order.getId(), "STUCK_PENDING",
                            "Order vẫn PENDING sau " + age.toMinutes()
                                    + " phút - Saga có thể đã bị kẹt, cần kiểm tra DLQ/log của Payment và Inventory Service"));
                }
            }
        }
    }
}
