package com.ledgerflow.payment.api;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.jayway.jsonpath.JsonPath;
import com.ledgerflow.order.model.OrderStatus;
import com.ledgerflow.order.model.entity.OrderEntity;
import com.ledgerflow.order.model.entity.OrderItemEntity;
import com.ledgerflow.order.service.OrderService;
import com.ledgerflow.payment.provider.PaymentProvider;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

/**
 * Full-stack idempotency tests for {@code POST /api/v1/payments} against a
 * real PostgreSQL instance, following the same Testcontainers
 * {@code @ServiceConnection} pattern as {@link PaymentControllerTest} — the
 * unique constraint that decides which concurrent request claims an
 * {@code Idempotency-Key} only means something against a real database.
 *
 * <p>{@link PaymentProvider} is spied on (not mocked) so the real
 * {@link com.ledgerflow.payment.provider.FakePaymentProvider} still runs,
 * while invocation counts prove the idempotency layer short-circuits
 * duplicate work.
 */
@SpringBootTest
@AutoConfigureWebTestClient
@Testcontainers
@ActiveProfiles("test")
class PaymentIdempotencyTest {

    private static final String PAYMENTS_URL = "/api/v1/payments";
    private static final String CUSTOMER_ID = "customer-1";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private OrderService orderService;

    @SpyBean
    private PaymentProvider paymentProvider;

    @BeforeEach
    void widenResponseTimeoutForPollingRetries() {
        // Concurrent-retry polling (ledgerflow.idempotency.max-poll-attempts=15,
        // backing off up to 1s each) can legitimately take several seconds to
        // converge, comfortably beyond WebTestClient's 5s default.
        webTestClient = webTestClient.mutate().responseTimeout(Duration.ofSeconds(20)).build();
    }

    private WebTestClient withScope(String scope) {
        return webTestClient.mutateWith(mockJwt()
                .jwt(jwt -> jwt.subject(CUSTOMER_ID))
                .authorities(new SimpleGrantedAuthority("SCOPE_" + scope)));
    }

    private static OrderEntity newOrder() {
        List<OrderItemEntity> items = List.of(
                OrderItemEntity.builder().productId("sku-1").quantity(1).unitPrice(new BigDecimal("19.98")).build());
        return OrderEntity.createNew(CUSTOMER_ID, "USD", new BigDecimal("19.98"), items);
    }

    private OrderEntity paymentPendingOrder() {
        OrderEntity saved = orderService.save(newOrder()).block();
        OrderEntity fetched = orderService.get(saved.getId()).block();
        fetched.transitionTo(OrderStatus.PAYMENT_PENDING);
        return orderService.save(fetched).block();
    }

    private EntityExchangeResult<byte[]> authorize(String idempotencyKey, String body) {
        return withScope("payment:process").post().uri(PAYMENTS_URL)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectBody()
                .returnResult();
    }

    @Test
    void authorize_shouldReturn400_whenIdempotencyKeyHeaderIsMissing() {
        OrderEntity order = paymentPendingOrder();
        String body = """
                { "orderId": "%s", "simulateOutcome": "SUCCESS" }
                """.formatted(order.getId());

        withScope("payment:process").post().uri(PAYMENTS_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.title").isEqualTo("Missing request value");

        verify(paymentProvider, never()).authorize(any(), any());
    }

    @Test
    void authorize_shouldInvokeProviderOnce_andReturnIdenticalResponses_whenSameKeyAndSameBodySentTwice() {
        OrderEntity order = paymentPendingOrder();
        String key = UUID.randomUUID().toString();
        String body = """
                { "orderId": "%s", "simulateOutcome": "SUCCESS" }
                """.formatted(order.getId());

        EntityExchangeResult<byte[]> first = authorize(key, body);
        EntityExchangeResult<byte[]> second = authorize(key, body);

        assertThat(first.getStatus()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatus()).isEqualTo(first.getStatus());
        assertThat(second.getResponseHeaders().getLocation()).isEqualTo(first.getResponseHeaders().getLocation());
        assertThat(new String(second.getResponseBody(), StandardCharsets.UTF_8))
                .isEqualTo(new String(first.getResponseBody(), StandardCharsets.UTF_8));

        verify(paymentProvider, times(1)).authorize(any(), any());
    }

    @Test
    void authorize_shouldReturnUnprocessableEntity_andNotInvokeProviderAgain_whenSameKeyReusedWithDifferentBody() {
        OrderEntity orderA = paymentPendingOrder();
        OrderEntity orderB = paymentPendingOrder();
        String key = UUID.randomUUID().toString();
        String bodyA = """
                { "orderId": "%s", "simulateOutcome": "SUCCESS" }
                """.formatted(orderA.getId());
        String bodyB = """
                { "orderId": "%s", "simulateOutcome": "SUCCESS" }
                """.formatted(orderB.getId());

        authorize(key, bodyA);

        withScope("payment:process").post().uri(PAYMENTS_URL)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(bodyB)
                .exchange()
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.title").isEqualTo("Idempotency key reused");

        verify(paymentProvider, times(1)).authorize(any(), any());
    }

    @Test
    void authorize_shouldDeleteTheClaim_andAllowRetryToSucceed_afterTheWrappedOperationFails() {
        OrderEntity order = newOrder();
        orderService.save(order).block();
        String key = UUID.randomUUID().toString();
        String body = """
                { "orderId": "%s", "simulateOutcome": "SUCCESS" }
                """.formatted(order.getId());

        withScope("payment:process").post().uri(PAYMENTS_URL)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.title").isEqualTo("Order not payable");

        verify(paymentProvider, never()).authorize(any(), any());

        OrderEntity fetched = orderService.get(order.getId()).block();
        fetched.transitionTo(OrderStatus.PAYMENT_PENDING);
        orderService.save(fetched).block();

        withScope("payment:process").post().uri(PAYMENTS_URL)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.status").isEqualTo("AUTHORIZED");

        verify(paymentProvider, times(1)).authorize(any(), any());
    }

    @Test
    void authorize_shouldInvokeProviderExactlyOnce_whenManyConcurrentRequestsShareTheSameKeyAndBody() {
        OrderEntity order = paymentPendingOrder();
        String key = UUID.randomUUID().toString();
        String body = """
                { "orderId": "%s", "simulateOutcome": "SUCCESS" }
                """.formatted(order.getId());
        int concurrency = 15;

        List<EntityExchangeResult<byte[]>> results = Flux.range(0, concurrency)
                .flatMap(i -> Mono.fromCallable(() -> authorize(key, body)).subscribeOn(Schedulers.boundedElastic()),
                        concurrency)
                .collectList()
                .block(Duration.ofSeconds(20));

        assertThat(results).hasSize(concurrency)
                .allSatisfy(r -> assertThat(r.getStatus()).isEqualTo(HttpStatus.CREATED));

        Set<String> distinctPaymentIds = results.stream()
                .map(r -> JsonPath.<String>read(new String(r.getResponseBody(), StandardCharsets.UTF_8), "$.id"))
                .collect(Collectors.toSet());
        assertThat(distinctPaymentIds).as("every caller must see the identical payment id")
                .hasSize(1)
                .doesNotContainNull();

        verify(paymentProvider, times(1)).authorize(any(), any());
    }
}
