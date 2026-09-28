package com.ledgerflow.order.model;

public class InvalidStateTransitionException extends RuntimeException {

    public InvalidStateTransitionException(OrderStatus current, OrderStatus attempted) {
        super("cannot transition order from " + current + " to " + attempted);
    }
}
