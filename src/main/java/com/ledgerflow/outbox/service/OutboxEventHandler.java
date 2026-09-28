package com.ledgerflow.outbox.service;

import com.ledgerflow.outbox.model.entity.OutboxEventEntity;

import reactor.core.publisher.Mono;

public interface OutboxEventHandler {

    String eventType();

    String handlerName();

    Mono<Void> handle(OutboxEventEntity event);
}
