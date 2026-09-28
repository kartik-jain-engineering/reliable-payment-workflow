package com.ledgerflow.order.service;

import java.util.UUID;

/**
 * Raised when an order is requested by id but does not exist.
 *
 * <p>The repository lookup just completes empty; deciding that "absent" is
 * an error is {@link OrderService}'s call.
 */
public class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException(UUID orderId) {
        super("order not found: " + orderId);
    }
}
