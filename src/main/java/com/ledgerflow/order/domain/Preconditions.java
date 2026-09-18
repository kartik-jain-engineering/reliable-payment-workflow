package com.ledgerflow.order.domain;

final class Preconditions {

    private Preconditions() {
    }

    static void requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }
}
