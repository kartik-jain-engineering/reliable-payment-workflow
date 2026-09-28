package com.ledgerflow.common.web;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.ledgerflow.config.SecurityConfig;
import com.ledgerflow.idempotency.service.IdempotencyService;
import com.ledgerflow.payment.api.PaymentController;
import com.ledgerflow.payment.service.PaymentService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests for {@link CorrelationIdWebFilter}, proving the header
 * contract over real HTTP responses rather than calling {@code filter()}
 * directly. Deliberately hits an unauthenticated request (rejected with 401
 * by {@link SecurityConfig} before reaching {@link PaymentController}) since
 * this filter is ordered at {@code Ordered.HIGHEST_PRECEDENCE} and must run
 * — and stamp the response header — before Spring Security's own chain.
 */
@WebFluxTest(PaymentController.class)
@Import({SecurityConfig.class, CorrelationIdWebFilter.class})
class CorrelationIdWebFilterTest {

    private static final String BASE_URL = "/api/v1/payments";

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private PaymentService paymentService;

    @MockBean
    private IdempotencyService idempotencyService;

    @MockBean
    private ReactiveJwtDecoder jwtDecoder;

    private String correlationIdOf(WebTestClient.ResponseSpec response) {
        return response.expectStatus().isUnauthorized()
                .returnResult(Void.class)
                .getResponseHeaders()
                .getFirst(CorrelationIdWebFilter.HEADER_NAME);
    }

    @Test
    void filter_shouldGenerateUuidShapedCorrelationId_whenHeaderIsAbsent() {
        String correlationId = correlationIdOf(webTestClient.get().uri(BASE_URL + "/{id}", UUID.randomUUID())
                .exchange());

        assertThat(correlationId).isNotBlank();
        assertThat(UUID.fromString(correlationId)).isNotNull();
    }

    @Test
    void filter_shouldEchoTheSameId_whenRequestHeaderIsValid() {
        String sent = "trace-abc.123_XYZ";

        String correlationId = correlationIdOf(webTestClient.get().uri(BASE_URL + "/{id}", UUID.randomUUID())
                .header(CorrelationIdWebFilter.HEADER_NAME, sent)
                .exchange());

        assertThat(correlationId).isEqualTo(sent);
    }

    @Test
    void filter_shouldReplaceWithAGeneratedId_whenRequestHeaderDoesNotMatchTheAcceptedPattern() {
        String invalid = "not a valid id!";

        String correlationId = correlationIdOf(webTestClient.get().uri(BASE_URL + "/{id}", UUID.randomUUID())
                .header(CorrelationIdWebFilter.HEADER_NAME, invalid)
                .exchange());

        assertThat(correlationId).isNotEqualTo(invalid);
        assertThat(UUID.fromString(correlationId)).isNotNull();
    }
}
