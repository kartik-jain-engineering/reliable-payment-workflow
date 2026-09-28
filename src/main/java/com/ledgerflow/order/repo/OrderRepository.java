package com.ledgerflow.order.repo;

import java.util.UUID;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;

import com.ledgerflow.order.model.entity.OrderEntity;

@Repository
public interface OrderRepository extends ReactiveCrudRepository<OrderEntity, UUID> {
}
