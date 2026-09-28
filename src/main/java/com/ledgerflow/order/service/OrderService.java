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

/**
 * Order use cases: create, fetch and cancel.
 *
 * <p>Persists an order as an explicit multi-statement write, not a cascaded
 * {@code save()}: Spring Data R2DBC has no aggregate/one-to-many mechanism
 * equivalent to Spring Data JDBC's {@code @MappedCollection}, so
 * {@link #save} writes the {@code orders} row itself via
 * {@link OrderRepository}, then deletes and re-inserts the order's
 * {@link OrderItemEntity} rows wholesale via {@link OrderItemRepository},
 * rather than diffing individual items.
 *
 * <p>Re-validates every entity with the injected Bean Validation
 * {@link Validator} before it is written, on top of the {@code @Valid}
 * checks already performed on the {@code model.dto} request at the API
 * boundary — defence in depth for any caller that builds an
 * {@code OrderEntity} without going through the controller.
 */
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final Validator validator;

    /**
     * {@code orderRepository.save(order)} is wrapped in {@link Mono#defer}
     * deliberately: {@code Mono.then(Mono)} still evaluates its argument
     * eagerly as a plain method call when the chain is assembled, so without
     * the deferral the repository would be called even when
     * {@link #validate} is about to signal an error — {@code defer} pushes
     * that call inside the subscription, after validation has passed.
     */
    @Transactional
    public Mono<OrderEntity> save(OrderEntity order) {
        return validate(order)
                .then(Mono.defer(() -> orderRepository.save(order)))
                .flatMap(saved -> replaceItems(saved, order.getItems()));
    }

    public Mono<OrderEntity> get(UUID orderId) {
        return findWithItems(orderId)
                .switchIfEmpty(Mono.error(() -> new OrderNotFoundException(orderId)));
    }

    public Mono<OrderEntity> cancel(UUID orderId) {
        return get(orderId)
                .flatMap(order -> {
                    order.cancel();
                    return save(order);
                });
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

    /**
     * Deletes every {@code order_items} row for {@code savedOrder} and
     * re-inserts {@code items} wholesale. Every re-inserted item is forced
     * back to {@code isNew() == true} regardless of where it came from
     * (a brand-new order or one just reloaded for {@link #cancel}): the
     * delete means any subsequent write for the same id must be an insert,
     * never an update, and {@code ReactiveCrudRepository.save()} would
     * otherwise treat an already-persisted item id as an update.
     */
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
