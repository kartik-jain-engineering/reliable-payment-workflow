package com.ledgerflow.order.model;

/**
 * Raised by {@code OrderEntity#transitionTo(OrderStatus)} when a caller
 * attempts a status change that the order lifecycle does not allow.
 *
 * <p>Lives alongside {@link OrderStatus} rather than in {@code service}
 * because it is raised directly by the entity's own state-machine method,
 * not by a lookup or persistence operation.
 */
public class InvalidStateTransitionException extends RuntimeException {

    public InvalidStateTransitionException(OrderStatus current, OrderStatus attempted) {
        super("cannot transition order from " + current + " to " + attempted);
    }
}
