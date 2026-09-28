package com.ledgerflow.payment.service;

import java.util.UUID;

import com.ledgerflow.order.model.OrderStatus;

public class OrderNotPayableException extends RuntimeException {

    public OrderNotPayableException(UUID orderId, OrderStatus status) {
        super("order " + orderId + " is not awaiting payment: " + status);
    }
}
