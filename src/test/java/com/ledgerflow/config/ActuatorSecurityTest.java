package com.ledgerflow.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

/**
 * Full-stack security-rule tests for {@code /actuator/prometheus}: it moved
 * from open/denied to {@code .authenticated()} in {@link SecurityConfig}, so
 * this asserts both halves of that contract against the real filter chain
 * (a {@code @WebFluxTest} slice would never register actuator endpoints).
 *
 * <p>{@code @SpringBootTest} disables metrics export by default via a
 * high-precedence internal "test" property source, which leaves
 * {@code PrometheusScrapeEndpoint} unregistered and the URL 404ing unless
 * explicitly re-enabled here at equally high precedence via
 * {@code @DynamicPropertySource}.
 */
@SpringBootTest
@AutoConfigureWebTestClient
@Testcontainers
@ActiveProfiles("test")
class ActuatorSecurityTest {

    private static final String PROMETHEUS_URL = "/actuator/prometheus";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void enableMetricsExport(DynamicPropertyRegistry registry) {
        registry.add("management.defaults.metrics.export.enabled", () -> "true");
        registry.add("management.prometheus.metrics.export.enabled", () -> "true");
    }

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void prometheus_shouldReturn401_whenNoToken() {
        webTestClient.get().uri(PROMETHEUS_URL)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void prometheus_shouldReturn200_whenAuthenticatedWithAnyScope() {
        webTestClient.mutateWith(mockJwt()
                        .jwt(jwt -> jwt.subject("customer-1"))
                        .authorities(new SimpleGrantedAuthority("SCOPE_payment:read")))
                .get().uri(PROMETHEUS_URL)
                .exchange()
                .expectStatus().isOk();
    }
}
