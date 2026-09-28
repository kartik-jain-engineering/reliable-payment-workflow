package com.ledgerflow.order.model.entity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.ledgerflow.order.model.InvalidStateTransitionException;
import com.ledgerflow.order.model.OrderStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pure unit tests for {@link OrderEntity}'s lifecycle state machine and
 * {@link OrderEntity#createNew} factory: no Spring context, no persistence.
 *
 * <p>Field-validity rules (blank {@code customerId}, invalid currency, etc.)
 * are no longer thrown from a constructor — they are Bean Validation
 * constraints checked by {@link OrderEntityValidationTest} instead.
 */
class OrderEntityTest {

    private static List<OrderItemEntity> oneItem() {
        return List.of(OrderItemEntity.builder()
                .productId("sku-1")
                .quantity(2)
                .unitPrice(new BigDecimal("9.99"))
                .build());
    }

    // ---------------------------------------------------------------
    // createNew
    // ---------------------------------------------------------------

    @Test
    void createNew_shouldAssignGeneratedId_andCreatedStatus_andPreserveFields() {
        OrderEntity order = OrderEntity.createNew("customer-1", "USD", new BigDecimal("19.98"), oneItem());

        assertThat(order.getId()).isNotNull();
        assertThat(order.isNew()).isTrue();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CREATED);
        assertThat(order.getCustomerId()).isEqualTo("customer-1");
        assertThat(order.getCurrency()).isEqualTo("USD");
        assertThat(order.getTotalAmount()).isEqualByComparingTo("19.98");
        assertThat(order.getItems()).hasSize(1);
        assertThat(order.getItems().get(0).getProductId()).isEqualTo("sku-1");
    }

    @Test
    void createNew_shouldGenerateDifferentIds_forEachCall() {
        OrderEntity first = OrderEntity.createNew("customer-1", "USD", BigDecimal.TEN, oneItem());
        OrderEntity second = OrderEntity.createNew("customer-1", "USD", BigDecimal.TEN, oneItem());

        assertThat(first.getId()).isNotEqualTo(second.getId());
    }

    @Test
    void createNew_shouldStampEachItem_withAFreshIdAndTheOrderId() {
        OrderEntity order = OrderEntity.createNew("customer-1", "USD", BigDecimal.TEN, oneItem());

        OrderItemEntity item = order.getItems().get(0);
        assertThat(item.getId()).isNotNull();
        assertThat(item.getOrderId()).isEqualTo(order.getId());
        assertThat(item.isNew()).isTrue();
    }

    @Test
    void createNew_shouldSetCreatedAtAndUpdatedAt_toTheSameNonNullInstant() {
        OrderEntity order = OrderEntity.createNew("customer-1", "USD", BigDecimal.TEN, oneItem());

        assertThat(order.getCreatedAt()).isNotNull();
        assertThat(order.getUpdatedAt()).isNotNull();
        assertThat(order.getCreatedAt()).isEqualTo(order.getUpdatedAt());
    }

    @Test
    void createNew_shouldSeedVersionToZero() {
        OrderEntity order = OrderEntity.createNew("customer-1", "USD", BigDecimal.TEN, oneItem());

        assertThat(order.getVersion()).isZero();
    }

    @Test
    void getItems_shouldBeDefensivelyUnmodifiable() {
        OrderEntity order = OrderEntity.createNew("customer-1", "USD", BigDecimal.TEN, oneItem());
        List<OrderItemEntity> items = order.getItems();

        assertThrows(UnsupportedOperationException.class,
                () -> items.add(OrderItemEntity.builder().productId("sku-2").quantity(1).unitPrice(BigDecimal.ONE)
                        .build()));
    }

    @Test
    void getItems_shouldNeverReturnNull_evenWhenLoadedThroughTheNoArgsConstructor() {
        // Mirrors how Spring Data materialises a row: no-args constructor, no
        // setItems() call yet.
        OrderEntity order = new OrderEntity();

        assertThat(order.getItems()).isEmpty();
    }

    // ---------------------------------------------------------------
    // Valid transitions
    // ---------------------------------------------------------------

    static Stream<Arguments> validTransitions() {
        return Stream.of(
                Arguments.of(OrderStatus.CREATED, OrderStatus.PAYMENT_PENDING),
                Arguments.of(OrderStatus.CREATED, OrderStatus.CANCELLED),
                Arguments.of(OrderStatus.PAYMENT_PENDING, OrderStatus.CONFIRMED),
                Arguments.of(OrderStatus.PAYMENT_PENDING, OrderStatus.FAILED),
                Arguments.of(OrderStatus.PAYMENT_PENDING, OrderStatus.CANCELLED));
    }

    @ParameterizedTest(name = "{0} -> {1} is allowed")
    @MethodSource("validTransitions")
    void transitionTo_shouldSucceed_forEachAllowedTransition(OrderStatus from, OrderStatus to) {
        OrderEntity order = withStatus(from);

        order.transitionTo(to);

        assertThat(order.getStatus()).isEqualTo(to);
    }

    @Test
    void transitionTo_shouldUpdateUpdatedAt_onSuccess() {
        OffsetDateTime epoch = OffsetDateTime.parse("1970-01-01T00:00:00Z");
        OrderEntity order = withStatus(OrderStatus.CREATED, epoch, epoch);

        order.transitionTo(OrderStatus.PAYMENT_PENDING);

        assertThat(order.getUpdatedAt()).isAfter(epoch);
    }

    @Test
    void cancel_shouldTransitionCreatedOrderToCancelled() {
        OrderEntity order = withStatus(OrderStatus.CREATED);

        order.cancel();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    // ---------------------------------------------------------------
    // Invalid transitions
    // ---------------------------------------------------------------

    static Stream<Arguments> invalidTransitions() {
        return Stream.of(
                // every self-transition
                Arguments.of(OrderStatus.CREATED, OrderStatus.CREATED),
                Arguments.of(OrderStatus.PAYMENT_PENDING, OrderStatus.PAYMENT_PENDING),
                Arguments.of(OrderStatus.CONFIRMED, OrderStatus.CONFIRMED),
                Arguments.of(OrderStatus.CANCELLED, OrderStatus.CANCELLED),
                Arguments.of(OrderStatus.FAILED, OrderStatus.FAILED),
                // out of each terminal state
                Arguments.of(OrderStatus.CONFIRMED, OrderStatus.CREATED),
                Arguments.of(OrderStatus.CANCELLED, OrderStatus.CREATED),
                Arguments.of(OrderStatus.FAILED, OrderStatus.PAYMENT_PENDING),
                // skipping straight to a terminal state that requires PAYMENT_PENDING first
                Arguments.of(OrderStatus.CREATED, OrderStatus.CONFIRMED),
                Arguments.of(OrderStatus.CREATED, OrderStatus.FAILED));
    }

    @ParameterizedTest(name = "{0} -> {1} is rejected")
    @MethodSource("invalidTransitions")
    void transitionTo_shouldThrow_forEveryDisallowedTransition(OrderStatus from, OrderStatus to) {
        OrderEntity order = withStatus(from);

        InvalidStateTransitionException exception =
                assertThrows(InvalidStateTransitionException.class, () -> order.transitionTo(to));

        assertThat(exception.getMessage()).contains(from.toString(), String.valueOf(to));
        assertThat(order.getStatus()).isEqualTo(from);
    }

    @Test
    void transitionTo_shouldThrow_whenTargetIsNull() {
        OrderEntity order = withStatus(OrderStatus.CREATED);

        assertThrows(InvalidStateTransitionException.class, () -> order.transitionTo(null));
    }

    @Test
    void cancel_shouldThrow_whenOrderAlreadyCancelled() {
        OrderEntity order = withStatus(OrderStatus.CANCELLED);

        assertThrows(InvalidStateTransitionException.class, order::cancel);
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private static OrderEntity withStatus(OrderStatus status) {
        OffsetDateTime now = OffsetDateTime.now();
        return withStatus(status, now, now);
    }

    private static OrderEntity withStatus(OrderStatus status, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        return OrderEntity.builder()
                .id(UUID.randomUUID())
                .customerId("customer-1")
                .currency("USD")
                .totalAmount(BigDecimal.TEN)
                .statusCode(status.name())
                .version(1L)
                .createdAt(createdAt)
                .updatedAt(updatedAt)
                .items(oneItem())
                .build();
    }
}
