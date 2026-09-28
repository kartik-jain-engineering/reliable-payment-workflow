package com.ledgerflow.payment.model;

public class InvalidStateTransitionException extends RuntimeException {

    public InvalidStateTransitionException(PaymentStatus current, PaymentStatus attempted) {
        super("cannot transition payment from " + current + " to " + attempted);
    }
}
