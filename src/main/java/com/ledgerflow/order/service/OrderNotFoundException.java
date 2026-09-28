package com.ledgerflow.order.service;

import java.util.UUID;

public class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException(UUID orderId) {
        super("order not found: " + orderId);
    }
}
