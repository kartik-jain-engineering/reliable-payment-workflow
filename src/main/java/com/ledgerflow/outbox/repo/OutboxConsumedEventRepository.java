package com.ledgerflow.outbox.repo;

import java.util.UUID;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;

import com.ledgerflow.outbox.model.entity.OutboxConsumedEventEntity;

@Repository
public interface OutboxConsumedEventRepository extends ReactiveCrudRepository<OutboxConsumedEventEntity, UUID> {
}
