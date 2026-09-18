package com.ledgerflow.order.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pure unit tests for the {@link Order} aggregate: no Spring context, no
 * persistence — every assertion runs directly against the aggregate's own
 * invariants and lifecycle rules.
 */
class OrderTest {

    private static final List<OrderItem> ONE_ITEM =
            List.of(new OrderItem("sku-1", 2, new BigDecimal("9.99")));

    // ---------------------------------------------------------------
    // Creation
    // ---------------------------------------------------------------

    @Test
    void create_shouldAssignGeneratedId_andCreatedStatus_andPreserveFields() {
        Order order = Order.create("customer-1", "USD", new BigDecimal("19.98"), ONE_ITEM);

        assertThat(order.getId()).isNotNull();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CREATED);
        assertThat(order.getCustomerId()).isEqualTo("customer-1");
        assertThat(order.getCurrency()).isEqualTo("USD");
        assertThat(order.getTotalAmount()).isEqualByComparingTo("19.98");
        assertThat(order.getItems()).containsExactlyElementsOf(ONE_ITEM);
    }

    @Test
    void create_shouldGenerateDifferentIds_forEachCall() {
        Order first = Order.create("customer-1", "USD", BigDecimal.TEN, ONE_ITEM);
        Order second = Order.create("customer-1", "USD", BigDecimal.TEN, ONE_ITEM);

        assertThat(first.getId()).isNotEqualTo(second.getId());
    }

    @Test
    void create_shouldSetCreatedAtAndUpdatedAt_toTheSameNonNullInstant() {
        Order order = Order.create("customer-1", "USD", BigDecimal.TEN, ONE_ITEM);

        assertThat(order.getCreatedAt()).isNotNull();
        assertThat(order.getUpdatedAt()).isNotNull();
        assertThat(order.getCreatedAt()).isEqualTo(order.getUpdatedAt());
    }

    @Test
    void getItems_shouldBeDefensivelyUnmodifiable() {
        Order order = Order.create("customer-1", "USD", BigDecimal.TEN, ONE_ITEM);
        List<OrderItem> items = order.getItems();

        assertThrows(UnsupportedOperationException.class,
                () -> items.add(new OrderItem("sku-2", 1, BigDecimal.ONE)));
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
        Order order = rehydrateWithStatus(from);

        order.transitionTo(to);

        assertThat(order.getStatus()).isEqualTo(to);
    }

    @Test
    void transitionTo_shouldUpdateUpdatedAt_onSuccess() {
        Order order = rehydrateWithStatus(OrderStatus.CREATED, Instant.EPOCH, Instant.EPOCH);

        order.transitionTo(OrderStatus.PAYMENT_PENDING);

        assertThat(order.getUpdatedAt()).isAfter(Instant.EPOCH);
    }

    @Test
    void cancel_shouldTransitionCreatedOrderToCancelled() {
        Order order = rehydrateWithStatus(OrderStatus.CREATED);

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
        Order order = rehydrateWithStatus(from);

        InvalidStateTransitionException exception =
                assertThrows(InvalidStateTransitionException.class, () -> order.transitionTo(to));

        assertThat(exception.getMessage()).contains(from.toString(), String.valueOf(to));
        assertThat(order.getStatus()).isEqualTo(from);
    }

    @Test
    void transitionTo_shouldThrow_whenTargetIsNull() {
        Order order = rehydrateWithStatus(OrderStatus.CREATED);

        assertThrows(InvalidStateTransitionException.class, () -> order.transitionTo(null));
    }

    @Test
    void cancel_shouldThrow_whenOrderAlreadyCancelled() {
        Order order = rehydrateWithStatus(OrderStatus.CANCELLED);

        assertThrows(InvalidStateTransitionException.class, order::cancel);
    }

    // ---------------------------------------------------------------
    // Validation: customerId
    // ---------------------------------------------------------------

    @Test
    void create_shouldThrow_whenCustomerIdIsNull() {
        assertThrows(IllegalArgumentException.class,
                () -> Order.create(null, "USD", BigDecimal.TEN, ONE_ITEM));
    }

    @Test
    void create_shouldThrow_whenCustomerIdIsBlank() {
        assertThrows(IllegalArgumentException.class,
                () -> Order.create("   ", "USD", BigDecimal.TEN, ONE_ITEM));
    }

    // ---------------------------------------------------------------
    // Validation: currency
    // ---------------------------------------------------------------

    @Test
    void create_shouldThrow_whenCurrencyIsNull() {
        assertThrows(IllegalArgumentException.class,
                () -> Order.create("customer-1", null, BigDecimal.TEN, ONE_ITEM));
    }

    @Test
    void create_shouldThrow_whenCurrencyIsBlank() {
        assertThrows(IllegalArgumentException.class,
                () -> Order.create("customer-1", "  ", BigDecimal.TEN, ONE_ITEM));
    }

    @Test
    void create_shouldThrow_whenCurrencyIsWellFormedButNotARealIsoCode() {
        // "ZZZ" matches a naive [A-Z]{3} pattern but is not an ISO 4217 currency.
        assertThrows(IllegalArgumentException.class,
                () -> Order.create("customer-1", "ZZZ", BigDecimal.TEN, ONE_ITEM));
    }

    // ---------------------------------------------------------------
    // Validation: totalAmount
    // ---------------------------------------------------------------

    @Test
    void create_shouldThrow_whenTotalAmountIsNull() {
        assertThrows(IllegalArgumentException.class,
                () -> Order.create("customer-1", "USD", null, ONE_ITEM));
    }

    @Test
    void create_shouldThrow_whenTotalAmountIsZero() {
        assertThrows(IllegalArgumentException.class,
                () -> Order.create("customer-1", "USD", BigDecimal.ZERO, ONE_ITEM));
    }

    @Test
    void create_shouldThrow_whenTotalAmountIsNegative() {
        assertThrows(IllegalArgumentException.class,
                () -> Order.create("customer-1", "USD", new BigDecimal("-0.01"), ONE_ITEM));
    }

    // ---------------------------------------------------------------
    // Validation: items
    // ---------------------------------------------------------------

    @Test
    void create_shouldThrow_whenItemsIsNull() {
        assertThrows(IllegalArgumentException.class,
                () -> Order.create("customer-1", "USD", BigDecimal.TEN, null));
    }

    @Test
    void create_shouldThrow_whenItemsIsEmpty() {
        assertThrows(IllegalArgumentException.class,
                () -> Order.create("customer-1", "USD", BigDecimal.TEN, List.of()));
    }

    @Test
    void create_shouldThrow_whenItemsContainsNullEntry() {
        List<OrderItem> itemsWithNull = new ArrayList<>();
        itemsWithNull.add(null);

        assertThrows(IllegalArgumentException.class,
                () -> Order.create("customer-1", "USD", BigDecimal.TEN, itemsWithNull));
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private static Order rehydrateWithStatus(OrderStatus status) {
        Instant now = Instant.now();
        return rehydrateWithStatus(status, now, now);
    }

    private static Order rehydrateWithStatus(OrderStatus status, Instant createdAt, Instant updatedAt) {
        return Order.rehydrate(UUID.randomUUID(), "customer-1", "USD", BigDecimal.TEN, status, ONE_ITEM,
                createdAt, updatedAt);
    }
}
