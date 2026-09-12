package com.example.order.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response cho Frontend khi polling GET /orders/{id}/status.
 *
 * @JsonCreator + @JsonProperty: object này còn được dùng làm giá trị cache trong Redis
 * (xem OrderQueryService) - Jackson cần biết cách DỰNG LẠI object từ JSON khi đọc ra từ
 * cache (deserialize), không chỉ ghi (serialize). Vì class này không có constructor
 * rỗng + setter, phải khai báo rõ constructor nào dùng để deserialize.
 */
public class OrderStatusResponse {
    private final String status;
    private final String failReason; // null nếu không fail

    @JsonCreator
    public OrderStatusResponse(@JsonProperty("status") String status,
                                @JsonProperty("failReason") String failReason) {
        this.status = status;
        this.failReason = failReason;
    }

    public String getStatus() { return status; }
    public String getFailReason() { return failReason; }
}
