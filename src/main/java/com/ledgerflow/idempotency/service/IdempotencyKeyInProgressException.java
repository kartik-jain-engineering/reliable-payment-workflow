package com.ledgerflow.idempotency.service;

public class IdempotencyKeyInProgressException extends RuntimeException {

    public IdempotencyKeyInProgressException() {
        super("a request with this Idempotency-Key has not completed yet; retry later");
    }
}
