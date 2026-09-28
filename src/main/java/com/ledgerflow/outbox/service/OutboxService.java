package com.ledgerflow.outbox.service;

import java.util.Set;
import java.util.UUID;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerflow.outbox.model.entity.OutboxEventEntity;
import com.ledgerflow.outbox.model.event.DomainEvent;
import com.ledgerflow.outbox.repo.OutboxEventRepository;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class OutboxService {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final Validator validator;

    @Transactional(propagation = Propagation.MANDATORY)
    public Mono<Void> append(DomainEvent event) {
        return Mono.fromCallable(() -> OutboxEventEntity.pending(event, objectMapper.writeValueAsString(event)))
                .flatMap(this::persist)
                .then();
    }

    public Mono<OutboxEventEntity> markPublished(UUID eventId) {
        return outboxEventRepository.findById(eventId)
                .doOnNext(OutboxEventEntity::markPublished)
                .flatMap(this::persist);
    }

    public Mono<OutboxEventEntity> recordFailedAttempt(UUID eventId, String error, int maxAttempts) {
        return outboxEventRepository.findById(eventId)
                .doOnNext(event -> event.recordFailedAttempt(error, maxAttempts))
                .flatMap(this::persist);
    }

    private Mono<OutboxEventEntity> persist(OutboxEventEntity event) {
        return validate(event).then(Mono.defer(() -> outboxEventRepository.save(event)));
    }

    private Mono<Void> validate(OutboxEventEntity event) {
        Set<ConstraintViolation<OutboxEventEntity>> violations = validator.validate(event);
        if (violations.isEmpty()) {
            return Mono.empty();
        }
        return Mono.error(new ConstraintViolationException(violations));
    }
}
