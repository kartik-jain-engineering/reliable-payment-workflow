package com.ledgerflow.idempotency.service;

public class IdempotencyKeyReusedException extends RuntimeException {

    public IdempotencyKeyReusedException() {
        super("Idempotency-Key was already used with a different request payload");
    }
}
