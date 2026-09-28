package com.ledgerflow.outbox.repo;

import java.util.UUID;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;

import com.ledgerflow.outbox.model.entity.OutboxEventReceiptEntity;

@Repository
public interface OutboxEventReceiptRepository extends ReactiveCrudRepository<OutboxEventReceiptEntity, UUID> {
}
