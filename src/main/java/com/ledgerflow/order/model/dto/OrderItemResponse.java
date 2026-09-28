package com.ledgerflow.order.model.dto;

import java.math.BigDecimal;

public record OrderItemResponse(String productId, int quantity, BigDecimal unitPrice) {
}
