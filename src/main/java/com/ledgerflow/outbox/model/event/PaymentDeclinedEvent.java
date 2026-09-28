package com.ledgerflow.outbox.model.event;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record PaymentDeclinedEvent(
        UUID paymentId,
        UUID orderId,
        String customerId,
        BigDecimal amount,
        String currency,
        String status,
        OffsetDateTime declinedAt) implements DomainEvent {

    public static final String EVENT_TYPE = "PaymentDeclined";
    public static final int SCHEMA_VERSION = 1;

    @Override
    public String aggregateType() {
        return "Payment";
    }

    @Override
    public UUID aggregateId() {
        return paymentId;
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
