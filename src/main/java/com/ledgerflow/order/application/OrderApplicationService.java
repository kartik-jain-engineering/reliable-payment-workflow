package com.ledgerflow.order.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.ledgerflow.order.domain.Order;
import com.ledgerflow.order.domain.OrderItem;
import com.ledgerflow.order.domain.OrderNotFoundException;
import com.ledgerflow.order.domain.OrderRepository;

import reactor.core.publisher.Mono;

/**
 * Order use cases.
 *
 * <p>Depends only on the domain {@link OrderRepository} port, and returns
 * domain aggregates — persistence rows never reach this layer, let alone
 * leave it. The transaction boundary lives in the persistence adapter, which
 * is where the multi-statement order+items write actually happens.
 */
@Service
public class OrderApplicationService {

    private final OrderRepository orderRepository;

    public OrderApplicationService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    /**
     * Aggregate construction is deferred into the reactive pipeline so that a
     * rejected invariant surfaces as an {@code onError} signal rather than a
     * synchronous throw from the assembly call.
     */
    public Mono<Order> create(String customerId, String currency, BigDecimal totalAmount, List<OrderItem> items) {
        return Mono.fromCallable(() -> Order.create(customerId, currency, totalAmount, items))
                .flatMap(orderRepository::save);
    }

    public Mono<Order> get(UUID orderId) {
        return orderRepository.findById(orderId)
                .switchIfEmpty(Mono.error(() -> new OrderNotFoundException(orderId)));
    }

    public Mono<Order> cancel(UUID orderId) {
        return get(orderId)
                .flatMap(order -> {
                    order.cancel();
                    return orderRepository.save(order);
                });
    }
}
