package com.ledgerflow.payment.provider;

import java.util.UUID;

public class PaymentProviderTimeoutException extends RuntimeException {

    public PaymentProviderTimeoutException(UUID paymentId) {
        super("payment provider timed out for payment: " + paymentId);
    }
}
