package com.example.order.controller;

import com.example.order.dto.OrderSummaryReport;
import com.example.order.service.ReportService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint báo cáo (reporting) cho admin/dashboard - hoàn toàn tách khỏi OrderController
 * (vốn phục vụ Frontend/khách hàng) vì đối tượng gọi và mục đích sử dụng khác nhau.
 */
@RestController
@RequestMapping("/orders/report")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    /** GET /orders/report/summary -> số lượng & tổng tiền order theo từng status */
    @GetMapping("/summary")
    public OrderSummaryReport summary() {
        return reportService.summarize();
    }
}
