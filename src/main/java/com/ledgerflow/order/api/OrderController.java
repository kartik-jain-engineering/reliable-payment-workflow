package com.ledgerflow.order.api;

import java.net.URI;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import com.ledgerflow.order.application.OrderApplicationService;
import com.ledgerflow.order.domain.Order;

import reactor.core.publisher.Mono;

/**
 * REST endpoints for the order module.
 *
 * <p>Deliberately thin: parse, delegate, map. There is no endpoint for the
 * {@code PAYMENT_PENDING} / {@code CONFIRMED} / {@code FAILED} transitions —
 * those are driven by the payment workflow, not by clients.
 *
 * <p>Every handler returns {@code Mono<ResponseEntity<OrderResponse>>} rather
 * than a bare body, so the one endpoint that needs a {@code Location} header
 * does not have to be shaped differently from the rest.
 */
@RestController
@RequestMapping(OrderController.BASE_PATH)
public class OrderController {

    static final String BASE_PATH = "/api/v1/orders";

    private final OrderApplicationService orderApplicationService;

    public OrderController(OrderApplicationService orderApplicationService) {
        this.orderApplicationService = orderApplicationService;
    }

    @PostMapping
    public Mono<ResponseEntity<OrderResponse>> create(@Valid @RequestBody CreateOrderRequest request) {
        return orderApplicationService.create(
                        request.customerId(),
                        request.currency(),
                        request.totalAmount(),
                        OrderMapper.toDomainItems(request))
                .map(order -> ResponseEntity.created(locationOf(order)).body(OrderMapper.toResponse(order)));
    }

    @GetMapping("/{orderId}")
    public Mono<ResponseEntity<OrderResponse>> get(@PathVariable UUID orderId) {
        return orderApplicationService.get(orderId)
                .map(order -> ResponseEntity.ok(OrderMapper.toResponse(order)));
    }

    @PostMapping("/{orderId}/cancel")
    public Mono<ResponseEntity<OrderResponse>> cancel(@PathVariable UUID orderId) {
        return orderApplicationService.cancel(orderId)
                .map(order -> ResponseEntity.ok(OrderMapper.toResponse(order)));
    }

    /**
     * Built from the mapped path rather than the current request:
     * {@code ServletUriComponentsBuilder} is servlet-only and has no WebFlux
     * equivalent that reads from a thread-local.
     */
    private static URI locationOf(Order order) {
        return UriComponentsBuilder.fromPath(BASE_PATH + "/{orderId}")
                .buildAndExpand(order.getId())
                .toUri();
    }
}
