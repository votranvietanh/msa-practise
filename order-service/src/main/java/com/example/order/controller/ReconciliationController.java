package com.example.order.controller;

import com.example.order.dto.ReconciliationReport;
import com.example.order.service.ReconciliationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint đối soát (reconciliation) cho admin/vận hành - gọi lúc nghi ngờ có lệch dữ liệu
 * giữa Order Service và Payment Service, hoặc gọi định kỳ qua job/cronjob.
 */
@RestController
@RequestMapping("/orders/reconciliation")
public class ReconciliationController {

    private final ReconciliationService reconciliationService;

    public ReconciliationController(ReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    /** GET /orders/reconciliation -> danh sách order bị lệch dữ liệu với Payment Service */
    @GetMapping
    public ReconciliationReport reconcile() {
        return reconciliationService.reconcile();
    }
}
