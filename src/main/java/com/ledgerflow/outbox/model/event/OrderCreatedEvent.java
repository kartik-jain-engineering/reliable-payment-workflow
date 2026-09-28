package com.ledgerflow.outbox.model.event;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record OrderCreatedEvent(
        UUID orderId,
        String customerId,
        BigDecimal totalAmount,
        String currency,
        String status,
        OffsetDateTime createdAt) implements DomainEvent {

    public static final String EVENT_TYPE = "OrderCreated";
    public static final int SCHEMA_VERSION = 1;

    @Override
    public String aggregateType() {
        return "Order";
    }

    @Override
    public UUID aggregateId() {
        return orderId;
    }

    @Override
    public String eventType() {
        return EVENT_TYPE;
    }

    @Override
    public int schemaVersion() {
        return SCHEMA_VERSION;
    }
}
