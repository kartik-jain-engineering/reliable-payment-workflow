package com.ledgerflow.outbox.service.handler;

import org.springframework.stereotype.Service;

import com.ledgerflow.outbox.model.entity.OutboxEventEntity;
import com.ledgerflow.outbox.model.event.OrderCreatedEvent;
import com.ledgerflow.outbox.service.OutboxEventHandler;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class OrderCreatedReceiptHandler implements OutboxEventHandler {

    public static final String HANDLER_NAME = "order-created-receipt";

    private final EventReceiptRecorder receiptRecorder;

    @Override
    public String eventType() {
        return OrderCreatedEvent.EVENT_TYPE;
    }

    @Override
    public String handlerName() {
        return HANDLER_NAME;
    }

    @Override
    public Mono<Void> handle(OutboxEventEntity event) {
        return receiptRecorder.record(HANDLER_NAME, event);
    }
}
