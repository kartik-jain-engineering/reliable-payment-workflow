package com.ledgerflow.idempotency.repo;

import java.util.UUID;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;

import com.ledgerflow.idempotency.model.entity.IdempotencyKeyEntity;

import reactor.core.publisher.Mono;

@Repository
public interface IdempotencyKeyRepository extends ReactiveCrudRepository<IdempotencyKeyEntity, UUID> {

    Mono<IdempotencyKeyEntity> findByOwnerIdAndIdempotencyKey(String ownerId, String idempotencyKey);
}
