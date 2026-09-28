package com.ledgerflow.order.model.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.ledgerflow.order.model.OrderStatus;

public record OrderResponse(
        UUID id,
        String customerId,
        String currency,
        BigDecimal totalAmount,
        OrderStatus status,
        List<OrderItemResponse> items,
        Instant createdAt,
        Instant updatedAt) {
}
