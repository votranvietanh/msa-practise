package com.example.order.service;

import com.example.order.dto.OrderSummaryReport;
import com.example.order.entity.Order;
import com.example.order.entity.OrderStatus;
import com.example.order.repository.OrderRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/** Báo cáo tổng hợp đơn giản: đếm số order theo status + tổng tiền COMPLETED/FAILED. */
@Service
public class ReportService {

    private final OrderRepository orderRepository;

    public ReportService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    public OrderSummaryReport summarize() {
        List<Order> orders = orderRepository.findAll();

        long pending = countByStatus(orders, OrderStatus.PENDING);
        long completed = countByStatus(orders, OrderStatus.COMPLETED);
        long failed = countByStatus(orders, OrderStatus.FAILED);

        long totalAmountCompleted = sumAmountByStatus(orders, OrderStatus.COMPLETED);
        long totalAmountFailed = sumAmountByStatus(orders, OrderStatus.FAILED);

        return new OrderSummaryReport(orders.size(), pending, completed, failed,
                totalAmountCompleted, totalAmountFailed);
    }

    private long countByStatus(List<Order> orders, OrderStatus status) {
        return orders.stream().filter(o -> o.getStatus() == status).count();
    }

    private long sumAmountByStatus(List<Order> orders, OrderStatus status) {
        return orders.stream().filter(o -> o.getStatus() == status).mapToLong(Order::getAmount).sum();
    }
}
