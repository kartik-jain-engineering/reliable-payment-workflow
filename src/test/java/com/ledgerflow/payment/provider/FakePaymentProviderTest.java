package com.ledgerflow.payment.provider;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.ledgerflow.payment.model.SimulatedOutcome;
import com.ledgerflow.payment.model.entity.PaymentEntity;

import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link FakePaymentProvider}: no Spring context needed since
 * it has no collaborators. Asserts each {@link SimulatedOutcome} mode is
 * deterministic and that {@code TIMEOUT} never sleeps.
 */
class FakePaymentProviderTest {

    private final FakePaymentProvider provider = new FakePaymentProvider();

    private static PaymentEntity somePayment() {
        return PaymentEntity.createNew(UUID.randomUUID(), BigDecimal.TEN, "USD");
    }

    @Test
    void authorize_shouldSignalAuthorized_whenOutcomeIsSuccess() {
        StepVerifier.create(provider.authorize(somePayment(), SimulatedOutcome.SUCCESS))
                .expectNext(PaymentAuthorizationResult.AUTHORIZED)
                .verifyComplete();
    }

    @Test
    void authorize_shouldSignalDeclined_whenOutcomeIsDecline() {
        StepVerifier.create(provider.authorize(somePayment(), SimulatedOutcome.DECLINE))
                .expectNext(PaymentAuthorizationResult.DECLINED)
                .verifyComplete();
    }

    @Test
    void authorize_shouldSignalAuthorized_whenSimulatedOutcomeIsNull() {
        StepVerifier.create(provider.authorize(somePayment(), null))
                .expectNext(PaymentAuthorizationResult.AUTHORIZED)
                .verifyComplete();
    }

    @Test
    void authorize_shouldSignalPaymentProviderTimeoutException_whenOutcomeIsTimeout() {
        PaymentEntity payment = somePayment();

        StepVerifier.create(provider.authorize(payment, SimulatedOutcome.TIMEOUT))
                .expectErrorMatches(ex -> ex instanceof PaymentProviderTimeoutException
                        && ex.getMessage().contains(payment.getId().toString()))
                .verify();
    }

    @Test
    void authorize_shouldSignalTimeoutError_withoutAnyWallClockDelay() {
        // Guards the "never sleeps" contract: if a real delay were introduced
        // (e.g. Mono.delay(...).then(Mono.error(...))), this would time out or
        // take noticeably longer than a few milliseconds.
        StepVerifier.create(provider.authorize(somePayment(), SimulatedOutcome.TIMEOUT))
                .expectError(PaymentProviderTimeoutException.class)
                .verify(Duration.ofMillis(200));
    }

    @Test
    void authorize_shouldBeDeterministic_acrossRepeatedCallsWithTheSameOutcome() {
        PaymentEntity payment = somePayment();

        PaymentAuthorizationResult first = provider.authorize(payment, SimulatedOutcome.SUCCESS).block();
        PaymentAuthorizationResult second = provider.authorize(payment, SimulatedOutcome.SUCCESS).block();

        assertThat(first).isEqualTo(second).isEqualTo(PaymentAuthorizationResult.AUTHORIZED);
    }
}
