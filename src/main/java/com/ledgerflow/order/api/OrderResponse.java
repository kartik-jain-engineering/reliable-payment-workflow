package com.ledgerflow.order.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.ledgerflow.order.domain.OrderStatus;

/**
 * Read model returned by the order endpoints.
 *
 * <p>An explicit, standalone view: it neither wraps nor embeds the aggregate
 * or the persistence row, so the wire format can evolve independently of
 * either.
 */
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
