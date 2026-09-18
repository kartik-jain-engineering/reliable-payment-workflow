package com.ledgerflow.order.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Order aggregate root — owns its identity, timestamps and lifecycle rules.
 *
 * <p>{@link #create} assigns identity and timestamps; {@link #rehydrate}
 * restores them as stored, for the persistence adapter only. Optimistic-lock
 * versioning lives on {@code OrderRow}, not here — it's a persistence
 * concern, not a domain invariant.
 *
 * <p>Invariants are re-checked here even though the REST boundary validates
 * the same rules, since the aggregate must stay safe to use from any caller.
 */
public final class Order {

    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED_TRANSITIONS = allowedTransitions();

    private final UUID id;
    private final String customerId;
    private final String currency;
    private final BigDecimal totalAmount;
    private final List<OrderItem> items;
    private final Instant createdAt;

    private OrderStatus status;
    private Instant updatedAt;

    private Order(UUID id,
                  String customerId,
                  String currency,
                  BigDecimal totalAmount,
                  OrderStatus status,
                  List<OrderItem> items,
                  Instant createdAt,
                  Instant updatedAt) {
        this.id = id;
        this.customerId = customerId;
        this.currency = currency;
        this.totalAmount = totalAmount;
        this.status = status;
        this.items = List.copyOf(items);
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /**
     * Creates a brand-new order in state {@link OrderStatus#CREATED}.
     *
     * @throws IllegalArgumentException if any business invariant is violated
     */
    public static Order create(String customerId, String currency, BigDecimal totalAmount, List<OrderItem> items) {
        validateCustomerId(customerId);
        validateCurrency(currency);
        validateTotalAmount(totalAmount);
        validateItems(items);

        Instant now = Instant.now();
        return new Order(UUID.randomUUID(), customerId, currency, totalAmount, OrderStatus.CREATED, items, now, now);
    }

    /**
     * Rebuilds an order that already exists in the datastore.
     *
     * <p>Reserved for the persistence adapter: identity, status and
     * timestamps are restored as stored rather than re-derived.
     */
    public static Order rehydrate(UUID id,
                                  String customerId,
                                  String currency,
                                  BigDecimal totalAmount,
                                  OrderStatus status,
                                  List<OrderItem> items,
                                  Instant createdAt,
                                  Instant updatedAt) {
        return new Order(id, customerId, currency, totalAmount, status, items, createdAt, updatedAt);
    }

    /**
     * Moves the order to {@code target}, enforcing the lifecycle rules.
     *
     * @throws InvalidStateTransitionException if the transition is not allowed,
     *                                         including self-transitions, any
     *                                         move out of a terminal state and
     *                                         a {@code null} target
     */
    public void transitionTo(OrderStatus target) {
        if (target == null || !ALLOWED_TRANSITIONS.get(status).contains(target)) {
            throw new InvalidStateTransitionException(status, target);
        }
        this.status = target;
        this.updatedAt = Instant.now();
    }

    public void cancel() {
        transitionTo(OrderStatus.CANCELLED);
    }

    public UUID getId() {
        return id;
    }

    public String getCustomerId() {
        return customerId;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public OrderStatus getStatus() {
        return status;
    }

    /**
     * @return the line items, unmodifiable
     */
    public List<OrderItem> getItems() {
        return items;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    private static Map<OrderStatus, Set<OrderStatus>> allowedTransitions() {
        Map<OrderStatus, Set<OrderStatus>> transitions = new EnumMap<>(OrderStatus.class);
        transitions.put(OrderStatus.CREATED, EnumSet.of(OrderStatus.PAYMENT_PENDING, OrderStatus.CANCELLED));
        transitions.put(OrderStatus.PAYMENT_PENDING,
                EnumSet.of(OrderStatus.CONFIRMED, OrderStatus.FAILED, OrderStatus.CANCELLED));
        transitions.put(OrderStatus.CONFIRMED, EnumSet.noneOf(OrderStatus.class));
        transitions.put(OrderStatus.CANCELLED, EnumSet.noneOf(OrderStatus.class));
        transitions.put(OrderStatus.FAILED, EnumSet.noneOf(OrderStatus.class));
        return Map.copyOf(transitions);
    }

    private static void validateCustomerId(String customerId) {
        Preconditions.requireNonBlank(customerId, "customerId must not be blank");
    }

    private static void validateCurrency(String currency) {
        Preconditions.requireNonBlank(currency, "currency must not be blank");
        try {
            Currency.getInstance(currency);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("currency is not a valid ISO 4217 code: " + currency, ex);
        }
    }

    private static void validateTotalAmount(BigDecimal totalAmount) {
        if (totalAmount == null) {
            throw new IllegalArgumentException("totalAmount must not be null");
        }
        if (totalAmount.signum() <= 0) {
            throw new IllegalArgumentException("totalAmount must be positive, but was " + totalAmount);
        }
    }

    private static void validateItems(List<OrderItem> items) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("an order must contain at least one item");
        }
        // Not items.contains(null): the list arrives from Stream.toList(), whose
        // immutable implementation throws NPE on a null probe instead of returning false.
        if (items.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("order items must not contain null entries");
        }
    }
}
