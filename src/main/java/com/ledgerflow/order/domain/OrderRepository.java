package com.ledgerflow.order.domain;

import java.util.UUID;

import reactor.core.publisher.Mono;

/**
 * Persistence port for the {@link Order} aggregate.
 *
 * <p>A plain domain interface: no Spring Data types, no framework
 * annotations. Lookups complete empty when the order is absent — turning
 * "absent" into a 404 is the application layer's decision, not this port's.
 */
public interface OrderRepository {

    Mono<Order> save(Order order);

    Mono<Order> findById(UUID id);
}
