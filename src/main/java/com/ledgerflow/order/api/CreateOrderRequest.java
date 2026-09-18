package com.ledgerflow.order.api;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import com.ledgerflow.order.api.constraint.ValidCurrencyCode;

/**
 * Payload for {@code POST /api/v1/orders}.
 *
 * <p>Structural rules live here so malformed input is rejected with a 400 at
 * the boundary; the same rules are re-asserted as business invariants inside
 * the aggregate.
 */
public record CreateOrderRequest(

        @NotBlank
        String customerId,

        @NotNull
        @ValidCurrencyCode
        String currency,

        @NotNull
        @DecimalMin(value = "0", inclusive = false)
        BigDecimal totalAmount,

        @NotEmpty
        @Valid
        List<CreateOrderItemRequest> items) {
}
