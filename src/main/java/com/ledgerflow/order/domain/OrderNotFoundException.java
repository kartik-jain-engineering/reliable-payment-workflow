package com.ledgerflow.order.domain;

import java.util.UUID;

/**
 * Raised when an order is requested by id but does not exist.
 *
 * <p>The repository port itself just completes empty; deciding that "absent"
 * is an error is the application layer's call.
 */
public class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException(UUID orderId) {
        super("order not found: " + orderId);
    }
}
