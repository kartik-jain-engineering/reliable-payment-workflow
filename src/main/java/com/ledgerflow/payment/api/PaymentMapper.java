package com.ledgerflow.payment.api;

import com.ledgerflow.payment.model.dto.PaymentResponse;
import com.ledgerflow.payment.model.entity.PaymentEntity;

final class PaymentMapper {

    private PaymentMapper() {
    }


    static PaymentResponse toResponse(PaymentEntity payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getOrderId(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getStatus(),
                payment.getCreatedAt().toInstant(),
                payment.getUpdatedAt().toInstant());
    }
}
