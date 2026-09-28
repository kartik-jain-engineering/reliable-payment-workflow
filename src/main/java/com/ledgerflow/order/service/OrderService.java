package com.ledgerflow.order.service;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ledgerflow.order.model.entity.OrderEntity;
import com.ledgerflow.order.model.entity.OrderItemEntity;
import com.ledgerflow.order.repo.OrderItemRepository;
import com.ledgerflow.order.repo.OrderRepository;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final Validator validator;

    @Transactional
    public Mono<OrderEntity> save(OrderEntity order) {
        return validate(order)
                .then(Mono.defer(() -> orderRepository.save(order)))
                .flatMap(saved -> replaceItems(saved, order.getItems()));
    }

    @Transactional
    public Mono<OrderEntity> create(OrderEntity order, String customerId) {
        return requireOwnedBy(order, customerId)
                .flatMap(this::save);
    }

    public Mono<OrderEntity> get(UUID orderId) {
        return findWithItems(orderId)
                .switchIfEmpty(Mono.error(() -> new OrderNotFoundException(orderId)));
    }

    public Mono<OrderEntity> get(UUID orderId, String customerId) {
        return get(orderId)
                .flatMap(order -> requireOwnedBy(order, customerId));
    }

    public Mono<OrderEntity> cancel(UUID orderId) {
        return get(orderId)
                .flatMap(this::cancelAndSave);
    }

    public Mono<OrderEntity> cancel(UUID orderId, String customerId) {
        return get(orderId, customerId)
                .flatMap(this::cancelAndSave);
    }

    private Mono<OrderEntity> cancelAndSave(OrderEntity order) {
        order.cancel();
        return save(order);
    }

    private static Mono<OrderEntity> requireOwnedBy(OrderEntity order, String customerId) {
        if (order.getCustomerId().equals(customerId)) {
            return Mono.just(order);
        }
        return Mono.error(new OrderAccessDeniedException());
    }

    private Mono<OrderEntity> findWithItems(UUID orderId) {
        return orderRepository.findById(orderId)
                .flatMap(order -> orderItemRepository.findAllByOrderId(orderId)
                        .collectList()
                        .map(items -> {
                            order.setItems(items);
                            return order;
                        }));
    }


    private Mono<OrderEntity> replaceItems(OrderEntity savedOrder, List<OrderItemEntity> items) {
        List<OrderItemEntity> toInsert = items.stream()
                .map(item -> item.toBuilder()
                        .id(item.getId() == null ? UUID.randomUUID() : item.getId())
                        .orderId(savedOrder.getId())
                        .isNew(true)
                        .build())
                .toList();

        return orderItemRepository.deleteByOrderId(savedOrder.getId())
                .thenMany(Flux.fromIterable(toInsert).concatMap(orderItemRepository::save))
                .collectList()
                .map(savedItems -> {
                    savedOrder.setItems(savedItems);
                    return savedOrder;
                });
    }

    private Mono<Void> validate(OrderEntity order) {
        Set<ConstraintViolation<OrderEntity>> violations = validator.validate(order);
        if (violations.isEmpty()) {
            return Mono.empty();
        }
        return Mono.error(new ConstraintViolationException(violations));
    }
}
