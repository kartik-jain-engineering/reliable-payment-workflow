package com.ledgerflow.order.api;

import java.net.URI;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import com.ledgerflow.order.model.dto.CreateOrderRequest;
import com.ledgerflow.order.model.dto.OrderResponse;
import com.ledgerflow.order.model.entity.OrderEntity;
import com.ledgerflow.order.service.OrderService;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping(OrderController.BASE_PATH)
@RequiredArgsConstructor
public class OrderController {

    static final String BASE_PATH = "/api/v1/orders";

    private final OrderService orderService;

    @PostMapping
    public Mono<ResponseEntity<OrderResponse>> create(
            @Valid @RequestBody CreateOrderRequest request, @AuthenticationPrincipal Jwt jwt) {
        return orderService.create(OrderMapper.toNewEntity(request), jwt.getSubject())
                .map(order -> ResponseEntity.created(locationOf(order)).body(OrderMapper.toResponse(order)));
    }

    @GetMapping("/{orderId}")
    public Mono<ResponseEntity<OrderResponse>> get(@PathVariable UUID orderId, @AuthenticationPrincipal Jwt jwt) {
        return orderService.get(orderId, jwt.getSubject())
                .map(order -> ResponseEntity.ok(OrderMapper.toResponse(order)));
    }

    @PostMapping("/{orderId}/cancel")
    public Mono<ResponseEntity<OrderResponse>> cancel(@PathVariable UUID orderId, @AuthenticationPrincipal Jwt jwt) {
        return orderService.cancel(orderId, jwt.getSubject())
                .map(order -> ResponseEntity.ok(OrderMapper.toResponse(order)));
    }

    private static URI locationOf(OrderEntity order) {
        return UriComponentsBuilder.fromPath(BASE_PATH + "/{orderId}")
                .buildAndExpand(order.getId())
                .toUri();
    }
}
