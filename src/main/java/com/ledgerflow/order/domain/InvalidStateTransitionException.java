package com.ledgerflow.order.domain;

/**
 * Raised by the {@link Order} aggregate when a caller attempts a status
 * change that the order lifecycle does not allow.
 */
public class InvalidStateTransitionException extends RuntimeException {

    public InvalidStateTransitionException(OrderStatus current, OrderStatus attempted) {
        super("cannot transition order from " + current + " to " + attempted);
    }
}
