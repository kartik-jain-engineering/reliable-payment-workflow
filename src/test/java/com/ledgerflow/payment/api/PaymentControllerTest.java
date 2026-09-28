package com.ledgerflow.payment.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.ledgerflow.order.model.OrderStatus;
import com.ledgerflow.order.model.entity.OrderEntity;
import com.ledgerflow.order.model.entity.OrderItemEntity;
import com.ledgerflow.order.service.OrderService;

import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

/**
 * End-to-end tests for {@link PaymentController} against a real PostgreSQL
 * instance, following the same Testcontainers {@code @ServiceConnection}
 * pattern as {@code OrderServiceIntegrationTest} — the container contributes
 * both the {@code R2dbcConnectionDetails} the app uses and the
 * {@code JdbcConnectionDetails} Flyway needs to apply {@code V3__create_payments_schema.sql}.
 *
 * <p>Unlike {@code OrderControllerTest} (a {@code @WebFluxTest} slice with a
 * mocked service), this exercises the full stack — real {@link PaymentService},
 * real {@link OrderService}, real R2DBC repositories — because the scenarios
 * here (order status gating, payment persistence shape) only mean something
 * against an actual database.
 */
@SpringBootTest
@AutoConfigureWebTestClient
@Testcontainers
@ActiveProfiles("test")
class PaymentControllerTest {

    private static final String PAYMENTS_URL = "/api/v1/payments";
    private static final String CUSTOMER_ID = "customer-1";
    private static final String OTHER_CUSTOMER_ID = "customer-2";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private OrderService orderService;

    private WebTestClient withScope(String scope) {
        return withScope(CUSTOMER_ID, scope);
    }

    private WebTestClient withScope(String subject, String scope) {
        return webTestClient.mutateWith(mockJwt()
                .jwt(jwt -> jwt.subject(subject))
                .authorities(new SimpleGrantedAuthority("SCOPE_" + scope)));
    }

    private static OrderEntity newOrder() {
        return newOrderFor(CUSTOMER_ID);
    }

    private static OrderEntity newOrderFor(String customerId) {
        List<OrderItemEntity> items = List.of(
                OrderItemEntity.builder().productId("sku-1").quantity(1).unitPrice(new BigDecimal("19.98")).build());
        return OrderEntity.createNew(customerId, "USD", new BigDecimal("19.98"), items);
    }

    /**
     * Persists a fresh order and drives it into {@code PAYMENT_PENDING},
     * mirroring the real checkout flow the payment module expects to be
     * called from.
     */
    private OrderEntity paymentPendingOrder() {
        return paymentPendingOrderFor(CUSTOMER_ID);
    }

    private OrderEntity paymentPendingOrderFor(String customerId) {
        OrderEntity saved = orderService.save(newOrderFor(customerId)).block();
        OrderEntity fetched = orderService.get(saved.getId()).block();
        fetched.transitionTo(OrderStatus.PAYMENT_PENDING);
        return orderService.save(fetched).block();
    }

    // ---------------------------------------------------------------
    // POST /api/v1/payments
    // ---------------------------------------------------------------

    @Test
    void authorize_shouldReturn201WithAuthorizedPayment_onSuccess() {
        OrderEntity order = paymentPendingOrder();
        String body = """
                { "orderId": "%s", "simulateOutcome": "SUCCESS" }
                """.formatted(order.getId());

        withScope("payment:process").post().uri(PAYMENTS_URL)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().exists("Location")
                .expectBody()
                .jsonPath("$.orderId").isEqualTo(order.getId().toString())
                .jsonPath("$.status").isEqualTo("AUTHORIZED")
                .jsonPath("$.currency").isEqualTo("USD");

        StepVerifier.create(orderService.get(order.getId()))
                .assertNext(reloaded -> assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.CONFIRMED))
                .verifyComplete();
    }

    @Test
    void authorize_shouldReturn201WithDeclinedPayment_andFailTheOrder_whenSimulatedOutcomeIsDecline() {
        OrderEntity order = paymentPendingOrder();
        String body = """
                { "orderId": "%s", "simulateOutcome": "DECLINE" }
                """.formatted(order.getId());

        withScope("payment:process").post().uri(PAYMENTS_URL)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.status").isEqualTo("DECLINED");

        StepVerifier.create(orderService.get(order.getId()))
                .assertNext(reloaded -> assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.FAILED))
                .verifyComplete();
    }

    @Test
    void authorize_shouldReturn409_whenOrderIsNotAwaitingPayment() {
        OrderEntity order = newOrder();
        orderService.save(order).block();
        String body = """
                { "orderId": "%s" }
                """.formatted(order.getId());

        withScope("payment:process").post().uri(PAYMENTS_URL)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.title").isEqualTo("Order not payable");

        StepVerifier.create(orderService.get(order.getId()))
                .assertNext(reloaded -> assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.CREATED))
                .verifyComplete();
    }

    @Test
    void authorize_shouldReturn404_whenOrderDoesNotExist() {
        String body = """
                { "orderId": "%s" }
                """.formatted(UUID.randomUUID());

        withScope("payment:process").post().uri(PAYMENTS_URL)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.title").isEqualTo("Order not found");
    }

    // ---------------------------------------------------------------
    // GET /api/v1/payments/{paymentId}
    // ---------------------------------------------------------------

    @Test
    void get_shouldReturn200WithThePersistedPayment_afterAuthorization() {
        OrderEntity order = paymentPendingOrder();
        String createBody = """
                { "orderId": "%s", "simulateOutcome": "SUCCESS" }
                """.formatted(order.getId());

        String location = withScope("payment:process").post().uri(PAYMENTS_URL)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(createBody)
                .exchange()
                .expectStatus().isCreated()
                .returnResult(Void.class)
                .getResponseHeaders()
                .getLocation()
                .toString();

        withScope("payment:read").get().uri(location)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.orderId").isEqualTo(order.getId().toString())
                .jsonPath("$.status").isEqualTo("AUTHORIZED");
    }

    @Test
    void get_shouldReturn404_whenPaymentDoesNotExist() {
        UUID paymentId = UUID.randomUUID();

        withScope("payment:read").get().uri(PAYMENTS_URL + "/{paymentId}", paymentId)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.title").isEqualTo("Payment not found");
    }

    // ---------------------------------------------------------------
    // Resource ownership — ownership flows through the payment's order
    // ---------------------------------------------------------------

    @Test
    void authorize_shouldReturn403_whenOrderBelongsToAnotherCustomer() {
        OrderEntity order = paymentPendingOrderFor(OTHER_CUSTOMER_ID);
        String body = """
                { "orderId": "%s", "simulateOutcome": "SUCCESS" }
                """.formatted(order.getId());

        withScope("payment:process").post().uri(PAYMENTS_URL)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.title").isEqualTo("Access denied");
    }

    @Test
    void get_shouldReturn403_whenPaymentBelongsToAnotherCustomersOrder() {
        OrderEntity order = paymentPendingOrderFor(OTHER_CUSTOMER_ID);
        String createBody = """
                { "orderId": "%s", "simulateOutcome": "SUCCESS" }
                """.formatted(order.getId());

        String location = withScope(OTHER_CUSTOMER_ID, "payment:process")
                .post().uri(PAYMENTS_URL)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(createBody)
                .exchange()
                .expectStatus().isCreated()
                .returnResult(Void.class)
                .getResponseHeaders()
                .getLocation()
                .toString();

        withScope("payment:read").get().uri(location)
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.title").isEqualTo("Access denied");
    }
}
