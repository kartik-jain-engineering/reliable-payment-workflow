package com.ledgerflow.order.domain;

/**
 * Lifecycle states of an {@link Order}.
 *
 * <p>{@code CONFIRMED}, {@code CANCELLED} and {@code FAILED} are terminal;
 * the legal transitions between these states are enforced by
 * {@link Order#transitionTo(OrderStatus)}.
 */
public enum OrderStatus {

    CREATED,
    PAYMENT_PENDING,
    CONFIRMED,
    CANCELLED,
    FAILED
}
