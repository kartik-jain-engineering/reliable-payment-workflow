package com.ledgerflow.order.repo;

import java.util.UUID;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;

import com.ledgerflow.order.model.entity.OrderItemEntity;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface OrderItemRepository extends ReactiveCrudRepository<OrderItemEntity, UUID> {

    Flux<OrderItemEntity> findAllByOrderId(UUID orderId);

    Mono<Void> deleteByOrderId(UUID orderId);
}
