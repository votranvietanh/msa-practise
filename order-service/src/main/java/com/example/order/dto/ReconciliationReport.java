package com.example.order.dto;

import java.util.List;

/** Kết quả tổng hợp của 1 lần chạy đối soát: GET /orders/reconciliation */
public class ReconciliationReport {
    private final int totalOrdersChecked;
    private final int issuesFound;
    private final List<ReconciliationIssue> issues;

    public ReconciliationReport(int totalOrdersChecked, int issuesFound, List<ReconciliationIssue> issues) {
        this.totalOrdersChecked = totalOrdersChecked;
        this.issuesFound = issuesFound;
        this.issues = issues;
    }

    public int getTotalOrdersChecked() { return totalOrdersChecked; }
    public int getIssuesFound() { return issuesFound; }
    public List<ReconciliationIssue> getIssues() { return issues; }
}
