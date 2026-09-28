package com.ledgerflow.payment.api;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.ledgerflow.config.SecurityConfig;
import com.ledgerflow.idempotency.service.IdempotencyService;
import com.ledgerflow.payment.service.PaymentService;

import reactor.core.publisher.Mono;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

/**
 * Web-layer security-rule tests for {@link PaymentController}: no token,
 * malformed token and missing-scope all short-circuit in {@link SecurityConfig}'s
 * filter chain before the controller or its collaborators are ever invoked, so
 * {@link PaymentService} and {@link IdempotencyService} stay mocked and unstubbed.
 */
@WebFluxTest(PaymentController.class)
@Import(SecurityConfig.class)
class PaymentControllerSecurityTest {

    private static final String BASE_URL = "/api/v1/payments";
    private static final String CUSTOMER_ID = "customer-1";

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private PaymentService paymentService;

    @MockBean
    private IdempotencyService idempotencyService;

    @MockBean
    private ReactiveJwtDecoder jwtDecoder;

    private WebTestClient withScope(String scope) {
        return webTestClient.mutateWith(mockJwt()
                .jwt(jwt -> jwt.subject(CUSTOMER_ID))
                .authorities(new SimpleGrantedAuthority("SCOPE_" + scope)));
    }

    @Test
    void authorize_shouldReturn401_whenNoToken() {
        webTestClient.post().uri(BASE_URL)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void get_shouldReturn401_whenNoToken() {
        webTestClient.get().uri(BASE_URL + "/{paymentId}", UUID.randomUUID())
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void authorize_shouldReturn401_whenBearerTokenIsMalformed() {
        when(jwtDecoder.decode(anyString())).thenReturn(Mono.error(new BadJwtException("malformed token")));

        webTestClient.post().uri(BASE_URL)
                .header("Authorization", "Bearer not-a-real-jwt")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void authorize_shouldReturn403_whenScopeIsMissing() {
        withScope("payment:read").post().uri(BASE_URL)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void get_shouldReturn403_whenScopeIsMissing() {
        withScope("payment:process").get().uri(BASE_URL + "/{paymentId}", UUID.randomUUID())
                .exchange()
                .expectStatus().isForbidden();
    }
}
