package com.ledgerflow.outbox.repo;

import java.util.UUID;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;

import com.ledgerflow.outbox.model.entity.OutboxEventEntity;

import reactor.core.publisher.Flux;

@Repository
public interface OutboxEventRepository extends ReactiveCrudRepository<OutboxEventEntity, UUID> {

    @Query("SELECT * FROM outbox_events WHERE status = 'PENDING' ORDER BY created_at, id LIMIT :batchSize")
    Flux<OutboxEventEntity> findPendingBatch(int batchSize);
}
