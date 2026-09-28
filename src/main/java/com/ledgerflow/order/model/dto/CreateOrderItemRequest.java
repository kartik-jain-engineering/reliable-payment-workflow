package com.ledgerflow.order.model.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;


public record CreateOrderItemRequest(

        @NotBlank
        String productId,

        @Positive
        int quantity,

        @NotNull
        @PositiveOrZero
        BigDecimal unitPrice) {
}
