package com.ledgerflow.payment.repo;

import java.util.UUID;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;

import com.ledgerflow.payment.model.entity.PaymentEntity;

@Repository
public interface PaymentRepository extends ReactiveCrudRepository<PaymentEntity, UUID> {
}
