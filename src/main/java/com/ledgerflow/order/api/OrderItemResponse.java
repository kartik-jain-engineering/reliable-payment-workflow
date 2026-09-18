package com.ledgerflow.order.api;

import java.math.BigDecimal;

public record OrderItemResponse(String productId, int quantity, BigDecimal unitPrice) {
}
