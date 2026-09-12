package com.example.inventory.event;

/** Event Inventory Service PUBLISH khi trừ kho thành công (routing key: inventory.reserved) */
public class InventoryReservedEvent {
    private String orderId;

    public InventoryReservedEvent() {}
    public InventoryReservedEvent(String orderId) { this.orderId = orderId; }

    public String getOrderId() { return orderId; }
}
