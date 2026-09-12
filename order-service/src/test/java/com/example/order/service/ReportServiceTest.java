package com.example.order.service;

import com.example.order.dto.OrderSummaryReport;
import com.example.order.entity.Order;
import com.example.order.entity.OrderStatus;
import com.example.order.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Test
    void summarize_demDungSoLuongVaTongTien_theoTungStatus() {
        List<Order> orders = List.of(
                new Order("ORD-1", "U001", 100_000L, List.of(), OrderStatus.COMPLETED),
                new Order("ORD-2", "U001", 200_000L, List.of(), OrderStatus.COMPLETED),
                new Order("ORD-3", "U002", 50_000L, List.of(), OrderStatus.FAILED),
                new Order("ORD-4", "U002", 30_000L, List.of(), OrderStatus.PENDING)
        );
        when(orderRepository.findAll()).thenReturn(orders);

        OrderSummaryReport report = new ReportService(orderRepository).summarize();

        assertThat(report.getTotalOrders()).isEqualTo(4);
        assertThat(report.getCompleted()).isEqualTo(2);
        assertThat(report.getFailed()).isEqualTo(1);
        assertThat(report.getPending()).isEqualTo(1);
        assertThat(report.getTotalAmountCompleted()).isEqualTo(300_000L);
        assertThat(report.getTotalAmountFailed()).isEqualTo(50_000L);
    }
}
