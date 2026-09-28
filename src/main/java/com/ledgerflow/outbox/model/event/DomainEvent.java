package com.ledgerflow.outbox.model.event;

import java.util.UUID;

public interface DomainEvent {

    String aggregateType();

    UUID aggregateId();

    String eventType();

    int schemaVersion();
}
