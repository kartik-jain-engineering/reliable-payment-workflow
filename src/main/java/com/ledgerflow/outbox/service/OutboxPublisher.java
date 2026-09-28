package com.ledgerflow.outbox.service;

import org.springframework.stereotype.Service;

import com.ledgerflow.outbox.model.entity.OutboxEventEntity;
import com.ledgerflow.outbox.repo.OutboxEventRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxPublisher {

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxService outboxService;
    private final OutboxEventDispatcher dispatcher;
    private final OutboxProperties properties;

    public Mono<Void> publishPendingBatch() {
        return outboxEventRepository.findPendingBatch(properties.publisher().batchSize())
                .collectList()
                .flatMapMany(Flux::fromIterable)
                .concatMap(this::publish)
                .then();
    }

    private Mono<Void> publish(OutboxEventEntity event) {
        return dispatcher.dispatch(event)
                .then(Mono.defer(() -> markPublished(event)))
                .onErrorResume(ex -> recordFailure(event, ex))
                .onErrorResume(ex -> {
                    log.error("Could not record outbox publish failure for event {}", event.getId(), ex);
                    return Mono.empty();
                });
    }

    private Mono<Void> markPublished(OutboxEventEntity event) {
        return outboxService.markPublished(event.getId())
                .then()
                .onErrorResume(ex -> {
                    log.error("Outbox event {} dispatched but not marked published; left PENDING for redelivery",
                            event.getId(), ex);
                    return Mono.empty();
                });
    }

    private Mono<Void> recordFailure(OutboxEventEntity event, Throwable failure) {
        log.warn("Outbox event {} ({}) failed to publish", event.getId(), event.getEventType(), failure);
        return outboxService.recordFailedAttempt(
                        event.getId(),
                        failure.getClass().getName() + ": " + failure.getMessage(),
                        properties.publisher().maxAttempts())
                .then();
    }
}
