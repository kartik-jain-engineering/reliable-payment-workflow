package com.ledgerflow.order.model.dto;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import com.ledgerflow.order.model.constraint.ValidCurrencyCode;

public record CreateOrderRequest(

        @NotBlank
        String customerId,

        @NotNull
        @ValidCurrencyCode
        String currency,

        @NotNull
        @DecimalMin(value = "0", inclusive = false)
        BigDecimal totalAmount,

        @NotEmpty
        @Valid
        List<CreateOrderItemRequest> items) {
}
