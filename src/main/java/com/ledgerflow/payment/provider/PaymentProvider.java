package com.ledgerflow.payment.provider;

import com.ledgerflow.payment.model.SimulatedOutcome;
import com.ledgerflow.payment.model.entity.PaymentEntity;

import reactor.core.publisher.Mono;

public interface PaymentProvider {

    Mono<PaymentAuthorizationResult> authorize(PaymentEntity payment, SimulatedOutcome simulateOutcome);
}
