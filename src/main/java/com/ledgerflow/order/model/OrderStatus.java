package com.ledgerflow.order.model;

/**
 * Lifecycle states of an {@link com.ledgerflow.order.model.entity.OrderEntity}.
 *
 * <p>{@code CONFIRMED}, {@code CANCELLED} and {@code FAILED} are terminal;
 * the legal transitions between these states are enforced by
 * {@code OrderEntity#transitionTo(OrderStatus)}.
 */
public enum OrderStatus {

    CREATED,
    PAYMENT_PENDING,
    CONFIRMED,
    CANCELLED,
    FAILED
}
