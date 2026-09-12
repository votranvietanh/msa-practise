package com.example.order.dto;

/** Báo cáo tổng hợp số lượng & doanh thu theo status: GET /orders/report/summary */
public class OrderSummaryReport {
    private final long totalOrders;
    private final long pending;
    private final long completed;
    private final long failed;
    private final long totalAmountCompleted;
    private final long totalAmountFailed;

    public OrderSummaryReport(long totalOrders, long pending, long completed, long failed,
                               long totalAmountCompleted, long totalAmountFailed) {
        this.totalOrders = totalOrders;
        this.pending = pending;
        this.completed = completed;
        this.failed = failed;
        this.totalAmountCompleted = totalAmountCompleted;
        this.totalAmountFailed = totalAmountFailed;
    }

    public long getTotalOrders() { return totalOrders; }
    public long getPending() { return pending; }
    public long getCompleted() { return completed; }
    public long getFailed() { return failed; }
    public long getTotalAmountCompleted() { return totalAmountCompleted; }
    public long getTotalAmountFailed() { return totalAmountFailed; }
}
