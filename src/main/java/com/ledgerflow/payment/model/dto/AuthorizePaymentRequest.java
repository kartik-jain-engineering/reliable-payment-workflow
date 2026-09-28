package com.ledgerflow.payment.model.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

import com.ledgerflow.payment.model.SimulatedOutcome;

public record AuthorizePaymentRequest(

        @NotNull
        UUID orderId,

        SimulatedOutcome simulateOutcome) {
}
