package com.ledgerflow.outbox.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "ledgerflow.outbox.publisher", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublisherScheduler {

    private final OutboxPublisher outboxPublisher;

    @Scheduled(fixedDelayString = "${ledgerflow.outbox.publisher.poll-interval}")
    public void poll() {
        outboxPublisher.publishPendingBatch().block();
    }
}
