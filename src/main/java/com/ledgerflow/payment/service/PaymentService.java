package com.ledgerflow.payment.service;

import java.util.Set;
import java.util.UUID;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ledgerflow.order.model.OrderStatus;
import com.ledgerflow.order.model.entity.OrderEntity;
import com.ledgerflow.order.service.OrderService;
import com.ledgerflow.outbox.model.event.DomainEvent;
import com.ledgerflow.outbox.model.event.PaymentAuthorizedEvent;
import com.ledgerflow.outbox.model.event.PaymentDeclinedEvent;
import com.ledgerflow.outbox.service.OutboxService;
import com.ledgerflow.payment.model.PaymentStatus;
import com.ledgerflow.payment.model.SimulatedOutcome;
import com.ledgerflow.payment.model.entity.PaymentEntity;
import com.ledgerflow.payment.provider.PaymentAuthorizationResult;
import com.ledgerflow.payment.provider.PaymentProvider;
import com.ledgerflow.payment.provider.PaymentProviderTimeoutException;
import com.ledgerflow.payment.repo.PaymentRepository;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final OrderService orderService;
    private final PaymentProvider paymentProvider;
    private final OutboxService outboxService;
    private final Validator validator;

    @Transactional
    public Mono<PaymentEntity> authorize(UUID orderId, SimulatedOutcome simulateOutcome) {
        return orderService.get(orderId)
                .flatMap(order -> pay(order, simulateOutcome));
    }

    @Transactional
    public Mono<PaymentEntity> authorize(UUID orderId, SimulatedOutcome simulateOutcome, String customerId) {
        return orderService.get(orderId, customerId)
                .flatMap(order -> pay(order, simulateOutcome));
    }

    public Mono<PaymentEntity> get(UUID paymentId) {
        return paymentRepository.findById(paymentId)
                .switchIfEmpty(Mono.error(() -> new PaymentNotFoundException(paymentId)));
    }

    public Mono<PaymentEntity> get(UUID paymentId, String customerId) {
        return get(paymentId)
                .flatMap(payment -> orderService.get(payment.getOrderId(), customerId).thenReturn(payment));
    }

    private Mono<PaymentEntity> pay(OrderEntity order, SimulatedOutcome simulateOutcome) {
        return requirePaymentPending(order)
                .then(Mono.defer(() -> insertPending(order)))
                .flatMap(pending -> settle(pending, order, simulateOutcome));
    }

    private Mono<Void> requirePaymentPending(OrderEntity order) {
        if (order.getStatus() == OrderStatus.PAYMENT_PENDING) {
            return Mono.empty();
        }
        return Mono.error(new OrderNotPayableException(order.getId(), order.getStatus()));
    }

    private Mono<PaymentEntity> insertPending(OrderEntity order) {
        PaymentEntity payment =
                PaymentEntity.createNew(order.getId(), order.getTotalAmount(), order.getCurrency());

        return validate(payment)
                .then(Mono.defer(() -> paymentRepository.save(payment)))
                .map(saved -> saved.toBuilder().isNew(false).build());
    }

    private Mono<PaymentEntity> settle(PaymentEntity payment, OrderEntity order, SimulatedOutcome simulateOutcome) {
        return paymentProvider.authorize(payment, simulateOutcome)
                .flatMap(result -> result == PaymentAuthorizationResult.AUTHORIZED
                        ? complete(payment, PaymentStatus.AUTHORIZED, order, OrderStatus.CONFIRMED)
                        : complete(payment, PaymentStatus.DECLINED, order, OrderStatus.FAILED))
                .onErrorResume(PaymentProviderTimeoutException.class,
                        ex -> complete(payment, PaymentStatus.TIMED_OUT, order, OrderStatus.FAILED));
    }

    private Mono<PaymentEntity> complete(
            PaymentEntity payment, PaymentStatus paymentStatus, OrderEntity order, OrderStatus orderStatus) {
        payment.transitionTo(paymentStatus);
        order.transitionTo(orderStatus);

        return paymentRepository.save(payment)
                .flatMap(settled -> orderService.save(order).thenReturn(settled))
                .flatMap(settled -> outboxService.append(settledEvent(settled, order)).thenReturn(settled));
    }

    private static DomainEvent settledEvent(PaymentEntity payment, OrderEntity order) {
        if (payment.getStatus() == PaymentStatus.AUTHORIZED) {
            return new PaymentAuthorizedEvent(
                    payment.getId(),
                    payment.getOrderId(),
                    order.getCustomerId(),
                    payment.getAmount(),
                    payment.getCurrency(),
                    payment.getStatusCode(),
                    payment.getUpdatedAt());
        }
        return new PaymentDeclinedEvent(
                payment.getId(),
                payment.getOrderId(),
                order.getCustomerId(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getStatusCode(),
                payment.getUpdatedAt());
    }

    private Mono<Void> validate(PaymentEntity payment) {
        Set<ConstraintViolation<PaymentEntity>> violations = validator.validate(payment);
        if (violations.isEmpty()) {
            return Mono.empty();
        }
        return Mono.error(new ConstraintViolationException(violations));
    }
}
