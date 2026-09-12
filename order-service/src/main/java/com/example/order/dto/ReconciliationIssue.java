package com.example.order.dto;

/** 1 điểm lệch dữ liệu phát hiện được khi đối soát Order Service với Payment Service */
public class ReconciliationIssue {
    private final String orderId;
    private final String issueType;
    private final String description;

    public ReconciliationIssue(String orderId, String issueType, String description) {
        this.orderId = orderId;
        this.issueType = issueType;
        this.description = description;
    }

    public String getOrderId() { return orderId; }
    public String getIssueType() { return issueType; }
    public String getDescription() { return description; }
}
