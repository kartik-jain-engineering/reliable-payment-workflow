package com.ledgerflow.order.service;

public class OrderAccessDeniedException extends RuntimeException {

    public OrderAccessDeniedException() {
        super("order does not belong to the authenticated customer");
    }
}
