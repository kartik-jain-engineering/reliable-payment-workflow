package com.ledgerflow.order.api;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * A single line item within a {@link CreateOrderRequest}.
 *
 * <p>A zero {@code unitPrice} is accepted (free lines are legitimate); a
 * negative one is not.
 */
public record CreateOrderItemRequest(

        @NotBlank
        String productId,

        @Positive
        int quantity,

        @NotNull
        @PositiveOrZero
        BigDecimal unitPrice) {
}
