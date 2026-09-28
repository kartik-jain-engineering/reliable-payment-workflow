package com.ledgerflow.payment.model.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.ledgerflow.payment.model.PaymentStatus;

public record PaymentResponse(
        UUID id,
        UUID orderId,
        BigDecimal amount,
        String currency,
        PaymentStatus status,
        Instant createdAt,
        Instant updatedAt) {
}
