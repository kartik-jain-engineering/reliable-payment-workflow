package com.ledgerflow.outbox.service.handler;

import org.springframework.stereotype.Service;

import com.ledgerflow.outbox.model.entity.OutboxEventEntity;
import com.ledgerflow.outbox.model.event.PaymentDeclinedEvent;
import com.ledgerflow.outbox.service.OutboxEventHandler;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class PaymentDeclinedReceiptHandler implements OutboxEventHandler {

    public static final String HANDLER_NAME = "payment-declined-receipt";

    private final EventReceiptRecorder receiptRecorder;

    @Override
    public String eventType() {
        return PaymentDeclinedEvent.EVENT_TYPE;
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
