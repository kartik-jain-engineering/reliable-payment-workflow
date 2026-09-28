package com.ledgerflow.outbox.service.handler;

import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;

import org.springframework.stereotype.Service;

import com.ledgerflow.outbox.model.entity.OutboxEventEntity;
import com.ledgerflow.outbox.model.entity.OutboxEventReceiptEntity;
import com.ledgerflow.outbox.repo.OutboxEventReceiptRepository;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class EventReceiptRecorder {

    private final OutboxEventReceiptRepository receiptRepository;
    private final Validator validator;

    public Mono<Void> record(String handlerName, OutboxEventEntity event) {
        OutboxEventReceiptEntity receipt = OutboxEventReceiptEntity.of(handlerName, event);
        return validate(receipt)
                .then(Mono.defer(() -> receiptRepository.save(receipt)))
                .then();
    }

    private Mono<Void> validate(OutboxEventReceiptEntity receipt) {
        Set<ConstraintViolation<OutboxEventReceiptEntity>> violations = validator.validate(receipt);
        if (violations.isEmpty()) {
            return Mono.empty();
        }
        return Mono.error(new ConstraintViolationException(violations));
    }
}
