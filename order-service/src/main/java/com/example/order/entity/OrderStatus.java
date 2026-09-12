package com.example.order.entity;

/**
 * Vòng đời trạng thái của 1 order:
 *
 * PENDING (vừa tạo, đã publish order.created)
 *    |
 *    +--> COMPLETED  (nhận được inventory.reserved)
 *    |
 *    +--> FAILED     (nhận được payment.failed HOẶC inventory.failed)
 */
public enum OrderStatus {
    PENDING,
    COMPLETED,
    FAILED
}
