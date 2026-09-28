package com.ledgerflow.payment.api;

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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import com.ledgerflow.idempotency.service.IdempotencyService;
import com.ledgerflow.payment.model.dto.AuthorizePaymentRequest;
import com.ledgerflow.payment.model.dto.PaymentResponse;
import com.ledgerflow.payment.model.entity.PaymentEntity;
import com.ledgerflow.payment.service.PaymentService;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping(PaymentController.BASE_PATH)
@RequiredArgsConstructor
public class PaymentController {

    static final String BASE_PATH = "/api/v1/payments";
    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final PaymentService paymentService;
    private final IdempotencyService idempotencyService;

    @PostMapping
    public Mono<ResponseEntity<PaymentResponse>> authorize(
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @Valid @RequestBody AuthorizePaymentRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        String customerId = jwt.getSubject();
        return idempotencyService.execute(customerId, idempotencyKey, request, PaymentResponse.class,
                () -> paymentService.authorize(request.orderId(), request.simulateOutcome(), customerId)
                        .map(payment -> ResponseEntity.created(locationOf(payment))
                                .body(PaymentMapper.toResponse(payment))));
    }

    @GetMapping("/{paymentId}")
    public Mono<ResponseEntity<PaymentResponse>> get(@PathVariable UUID paymentId, @AuthenticationPrincipal Jwt jwt) {
        return paymentService.get(paymentId, jwt.getSubject())
                .map(payment -> ResponseEntity.ok(PaymentMapper.toResponse(payment)));
    }

    private static URI locationOf(PaymentEntity payment) {
        return UriComponentsBuilder.fromPath(BASE_PATH + "/{paymentId}")
                .buildAndExpand(payment.getId())
                .toUri();
    }
}
