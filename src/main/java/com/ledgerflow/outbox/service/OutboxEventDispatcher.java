package com.ledgerflow.outbox.service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;

import com.ledgerflow.outbox.model.entity.OutboxConsumedEventEntity;
import com.ledgerflow.outbox.model.entity.OutboxEventEntity;
import com.ledgerflow.outbox.repo.OutboxConsumedEventRepository;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class OutboxEventDispatcher {

    private final Map<String, List<OutboxEventHandler>> handlersByEventType;
    private final OutboxConsumedEventRepository consumedEventRepository;
    private final TransactionalOperator transactionalOperator;
    private final Validator validator;

    public OutboxEventDispatcher(
            List<OutboxEventHandler> handlers,
            OutboxConsumedEventRepository consumedEventRepository,
            TransactionalOperator transactionalOperator,
            Validator validator) {
        this.handlersByEventType = handlers.stream()
                .collect(Collectors.groupingBy(OutboxEventHandler::eventType));
        this.consumedEventRepository = consumedEventRepository;
        this.transactionalOperator = transactionalOperator;
        this.validator = validator;
    }

    public Mono<Void> dispatch(OutboxEventEntity event) {
        List<OutboxEventHandler> handlers = handlersByEventType.getOrDefault(event.getEventType(), List.of());
        if (handlers.isEmpty()) {
            return Mono.error(new IllegalStateException(
                    "No handler registered for outbox event type '" + event.getEventType() + "'"));
        }
        return Flux.fromIterable(handlers)
                .concatMap(handler -> consume(handler, event))
                .then();
    }

    private Mono<Void> consume(OutboxEventHandler handler, OutboxEventEntity event) {
        return transactionalOperator.transactional(claim(handler, event)
                        .then(Mono.defer(() -> handler.handle(event))))
                .onErrorResume(AlreadyConsumedException.class, ex -> Mono.empty());
    }

    private Mono<Void> claim(OutboxEventHandler handler, OutboxEventEntity event) {
        OutboxConsumedEventEntity claim = OutboxConsumedEventEntity.claim(handler.handlerName(), event.getId());
        return validate(claim)
                .then(Mono.defer(() -> consumedEventRepository.save(claim)))
                .then()
                .onErrorMap(DuplicateKeyException.class, AlreadyConsumedException::new);
    }

    private Mono<Void> validate(OutboxConsumedEventEntity claim) {
        Set<ConstraintViolation<OutboxConsumedEventEntity>> violations = validator.validate(claim);
        if (violations.isEmpty()) {
            return Mono.empty();
        }
        return Mono.error(new ConstraintViolationException(violations));
    }

    private static final class AlreadyConsumedException extends RuntimeException {

        private AlreadyConsumedException(DuplicateKeyException cause) {
            super(cause);
        }
    }
}
