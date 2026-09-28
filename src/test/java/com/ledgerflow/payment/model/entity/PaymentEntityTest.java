package com.ledgerflow.payment.model.entity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.ledgerflow.payment.model.InvalidStateTransitionException;
import com.ledgerflow.payment.model.PaymentStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pure unit tests for {@link PaymentEntity}'s lifecycle state machine and
 * {@link PaymentEntity#createNew} factory: no Spring context, no persistence.
 * Mirrors {@code OrderEntityTest}'s coverage shape for the payment module.
 */
class PaymentEntityTest {

    // ---------------------------------------------------------------
    // createNew
    // ---------------------------------------------------------------

    @Test
    void createNew_shouldAssignGeneratedId_andPendingStatus_andPreserveFields() {
        UUID orderId = UUID.randomUUID();
        PaymentEntity payment = PaymentEntity.createNew(orderId, new BigDecimal("19.98"), "USD");

        assertThat(payment.getId()).isNotNull();
        assertThat(payment.isNew()).isTrue();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payment.getOrderId()).isEqualTo(orderId);
        assertThat(payment.getCurrency()).isEqualTo("USD");
        assertThat(payment.getAmount()).isEqualByComparingTo("19.98");
    }

    @Test
    void createNew_shouldGenerateDifferentIds_forEachCall() {
        UUID orderId = UUID.randomUUID();
        PaymentEntity first = PaymentEntity.createNew(orderId, BigDecimal.TEN, "USD");
        PaymentEntity second = PaymentEntity.createNew(orderId, BigDecimal.TEN, "USD");

        assertThat(first.getId()).isNotEqualTo(second.getId());
    }

    @Test
    void createNew_shouldSetCreatedAtAndUpdatedAt_toTheSameNonNullInstant() {
        PaymentEntity payment = PaymentEntity.createNew(UUID.randomUUID(), BigDecimal.TEN, "USD");

        assertThat(payment.getCreatedAt()).isNotNull();
        assertThat(payment.getUpdatedAt()).isNotNull();
        assertThat(payment.getCreatedAt()).isEqualTo(payment.getUpdatedAt());
    }

    @Test
    void createNew_shouldSeedVersionToZero() {
        PaymentEntity payment = PaymentEntity.createNew(UUID.randomUUID(), BigDecimal.TEN, "USD");

        assertThat(payment.getVersion()).isZero();
    }

    // ---------------------------------------------------------------
    // Valid transitions
    // ---------------------------------------------------------------

    static Stream<Arguments> validTransitions() {
        return Stream.of(
                Arguments.of(PaymentStatus.PENDING, PaymentStatus.AUTHORIZED),
                Arguments.of(PaymentStatus.PENDING, PaymentStatus.DECLINED),
                Arguments.of(PaymentStatus.PENDING, PaymentStatus.TIMED_OUT));
    }

    @ParameterizedTest(name = "{0} -> {1} is allowed")
    @MethodSource("validTransitions")
    void transitionTo_shouldSucceed_forEachAllowedTransition(PaymentStatus from, PaymentStatus to) {
        PaymentEntity payment = withStatus(from);

        payment.transitionTo(to);

        assertThat(payment.getStatus()).isEqualTo(to);
    }

    @Test
    void transitionTo_shouldUpdateUpdatedAt_onSuccess() {
        OffsetDateTime epoch = OffsetDateTime.parse("1970-01-01T00:00:00Z");
        PaymentEntity payment = withStatus(PaymentStatus.PENDING, epoch, epoch);

        payment.transitionTo(PaymentStatus.AUTHORIZED);

        assertThat(payment.getUpdatedAt()).isAfter(epoch);
    }

    // ---------------------------------------------------------------
    // Invalid transitions
    // ---------------------------------------------------------------

    static Stream<Arguments> invalidTransitions() {
        return Stream.of(
                // every self-transition
                Arguments.of(PaymentStatus.PENDING, PaymentStatus.PENDING),
                Arguments.of(PaymentStatus.AUTHORIZED, PaymentStatus.AUTHORIZED),
                Arguments.of(PaymentStatus.DECLINED, PaymentStatus.DECLINED),
                Arguments.of(PaymentStatus.TIMED_OUT, PaymentStatus.TIMED_OUT),
                // out of each terminal state
                Arguments.of(PaymentStatus.AUTHORIZED, PaymentStatus.PENDING),
                Arguments.of(PaymentStatus.DECLINED, PaymentStatus.PENDING),
                Arguments.of(PaymentStatus.TIMED_OUT, PaymentStatus.PENDING),
                // terminal states can never cross-transition to one another
                Arguments.of(PaymentStatus.AUTHORIZED, PaymentStatus.DECLINED),
                Arguments.of(PaymentStatus.DECLINED, PaymentStatus.TIMED_OUT),
                Arguments.of(PaymentStatus.TIMED_OUT, PaymentStatus.AUTHORIZED));
    }

    @ParameterizedTest(name = "{0} -> {1} is rejected")
    @MethodSource("invalidTransitions")
    void transitionTo_shouldThrow_forEveryDisallowedTransition(PaymentStatus from, PaymentStatus to) {
        PaymentEntity payment = withStatus(from);

        InvalidStateTransitionException exception =
                assertThrows(InvalidStateTransitionException.class, () -> payment.transitionTo(to));

        assertThat(exception.getMessage()).contains(from.toString(), String.valueOf(to));
        assertThat(payment.getStatus()).isEqualTo(from);
    }

    @Test
    void transitionTo_shouldThrow_whenTargetIsNull() {
        PaymentEntity payment = withStatus(PaymentStatus.PENDING);

        assertThrows(InvalidStateTransitionException.class, () -> payment.transitionTo(null));
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private static PaymentEntity withStatus(PaymentStatus status) {
        OffsetDateTime now = OffsetDateTime.now();
        return withStatus(status, now, now);
    }

    private static PaymentEntity withStatus(PaymentStatus status, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        return PaymentEntity.builder()
                .id(UUID.randomUUID())
                .orderId(UUID.randomUUID())
                .amount(BigDecimal.TEN)
                .currency("USD")
                .statusCode(status.name())
                .version(1L)
                .createdAt(createdAt)
                .updatedAt(updatedAt)
                .build();
    }
}
