package com.ledgerflow.order.domain;

import java.math.BigDecimal;

/**
 * Immutable line item of an {@link Order}.
 *
 * <p>A value object: it has no identity of its own and is compared by value.
 * All invariants are enforced on construction, so an {@code OrderItem} that
 * exists is always valid.
 *
 * @param productId  identifier of the ordered product, never blank
 * @param quantity   number of units ordered, strictly positive
 * @param unitPrice  price per unit, never negative (zero is allowed for
 *                   give-aways and promotional lines)
 */
public record OrderItem(String productId, int quantity, BigDecimal unitPrice) {

    public OrderItem {
        Preconditions.requireNonBlank(productId, "order item productId must not be blank");
        if (quantity <= 0) {
            throw new IllegalArgumentException("order item quantity must be positive, but was " + quantity);
        }
        if (unitPrice == null) {
            throw new IllegalArgumentException("order item unitPrice must not be null");
        }
        if (unitPrice.signum() < 0) {
            throw new IllegalArgumentException("order item unitPrice must not be negative, but was " + unitPrice);
        }
    }
}
