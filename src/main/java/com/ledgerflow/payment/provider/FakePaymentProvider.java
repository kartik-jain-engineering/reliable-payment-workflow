package com.ledgerflow.payment.provider;

import org.springframework.stereotype.Component;

import com.ledgerflow.payment.model.SimulatedOutcome;
import com.ledgerflow.payment.model.entity.PaymentEntity;

import reactor.core.publisher.Mono;

@Component
public class FakePaymentProvider implements PaymentProvider {

    @Override
    public Mono<PaymentAuthorizationResult> authorize(PaymentEntity payment, SimulatedOutcome simulateOutcome) {
        SimulatedOutcome outcome = simulateOutcome == null ? SimulatedOutcome.SUCCESS : simulateOutcome;

        return switch (outcome) {
            case SUCCESS -> Mono.just(PaymentAuthorizationResult.AUTHORIZED);
            case DECLINE -> Mono.just(PaymentAuthorizationResult.DECLINED);
            case TIMEOUT -> Mono.error(new PaymentProviderTimeoutException(payment.getId()));
        };
    }
}
