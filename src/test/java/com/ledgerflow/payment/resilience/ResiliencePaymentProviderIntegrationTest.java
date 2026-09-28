package com.ledgerflow.payment.resilience;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
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
import com.ledgerflow.payment.provider.PaymentAuthorizationResult;
import com.ledgerflow.payment.provider.PaymentProvider;
import com.ledgerflow.payment.provider.PaymentProviderTimeoutException;
import com.ledgerflow.payment.provider.Resilience4jConfig;
import com.ledgerflow.payment.repo.PaymentRepository;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

/**
 * Full-stack tests for the resilience4j TimeLimiter/Retry/CircuitBreaker
 * stack wrapped around {@link PaymentProvider#authorize} in
 * {@code PaymentService.settle}, against a real PostgreSQL instance
 * (same {@code @ServiceConnection} pattern as {@link com.ledgerflow.payment.api.PaymentControllerTest}).
 *
 * <p>Runs under {@code test-resilience} on top of {@code test} so the
 * circuit breaker's {@code slidingWindowSize}/{@code minimumNumberOfCalls}
 * are small enough (4) to actually open within a handful of requests,
 * instead of the resilience4j default of 100. {@link PaymentProvider} is
 * spied so invocation counts prove retry/short-circuit behavior, and the
 * real {@link CircuitBreaker} instance is inspected directly rather than
 * inferred from HTTP status alone.
 */
@SpringBootTest
@AutoConfigureWebTestClient
@Testcontainers
@ActiveProfiles({"test", "test-resilience"})
class ResiliencePaymentProviderIntegrationTest {

    private static final String PAYMENTS_URL = "/api/v1/payments";
    private static final String CUSTOMER_ID = "customer-1";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private OrderService orderService;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @SpyBean
    private PaymentProvider paymentProvider;

    @BeforeEach
    void widenResponseTimeoutAndResetCircuitBreaker() {
        webTestClient = webTestClient.mutate().responseTimeout(Duration.ofSeconds(15)).build();
        circuitBreakerRegistry.circuitBreaker(Resilience4jConfig.PAYMENT_PROVIDER).reset();
    }

    @AfterEach
    void resetCircuitBreaker() {
        circuitBreakerRegistry.circuitBreaker(Resilience4jConfig.PAYMENT_PROVIDER).reset();
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

    private WebTestClient.ResponseSpec authorize(OrderEntity order, String simulateOutcome) {
        String body = """
                { "orderId": "%s", "simulateOutcome": "%s" }
                """.formatted(order.getId(), simulateOutcome);

        return withScope("payment:process").post().uri(PAYMENTS_URL)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange();
    }

    @Test
    void authorize_shouldInvokeProviderExactlyOnce_andNeverRetry_whenProviderDeclines() {
        OrderEntity order = paymentPendingOrder();

        authorize(order, "DECLINE")
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.status").isEqualTo("DECLINED");

        verify(paymentProvider, times(1)).authorize(any(), any());

        StepVerifier.create(orderService.get(order.getId()))
                .assertNext(reloaded -> assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.FAILED))
                .verifyComplete();
    }

    @Test
    void authorize_shouldSucceed_afterOneTransientTimeLimiterTimeout_retryingExactlyOnce() {
        OrderEntity order = paymentPendingOrder();
        AtomicInteger invocations = new AtomicInteger();
        doAnswer(invocation -> invocations.getAndIncrement() == 0
                ? Mono.just(PaymentAuthorizationResult.AUTHORIZED).delayElement(Duration.ofMillis(1500))
                : Mono.just(PaymentAuthorizationResult.AUTHORIZED))
                .when(paymentProvider).authorize(any(), any());

        authorize(order, "SUCCESS")
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.status").isEqualTo("AUTHORIZED");

        assertThat(invocations.get())
                .as("provider must be called a second time by Retry after the first call is cancelled by the "
                        + "1s TimeLimiter, but no more than maxAttempts=2 permits")
                .isEqualTo(2);

        StepVerifier.create(orderService.get(order.getId()))
                .assertNext(reloaded -> assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.CONFIRMED))
                .verifyComplete();
    }

    @Test
    void circuitBreaker_shouldOpen_afterEnoughFailures_andShortCircuitWithoutInvokingProviderOrPersistingAPayment() {
        doReturn(Mono.error(new PaymentProviderTimeoutException(UUID.randomUUID())))
                .when(paymentProvider).authorize(any(), any());

        for (int i = 0; i < 4; i++) {
            authorize(paymentPendingOrder(), "TIMEOUT")
                    .expectStatus().isCreated()
                    .expectBody()
                    .jsonPath("$.status").isEqualTo("TIMED_OUT");
        }

        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(Resilience4jConfig.PAYMENT_PROVIDER);
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        clearInvocations(paymentProvider);
        long paymentCountBeforeShortCircuit = paymentRepository.count().block();

        authorize(paymentPendingOrder(), "SUCCESS")
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.title").isEqualTo("Payment service temporarily unavailable");

        verify(paymentProvider, never()).authorize(any(), any());
        assertThat(paymentRepository.count().block())
                .as("the short-circuited call's insertPending() must roll back with no row left behind")
                .isEqualTo(paymentCountBeforeShortCircuit);
    }

    @Test
    void circuitBreaker_shouldTransitionThroughHalfOpenToClosed_onceWaitDurationElapsesAndCallsSucceed()
            throws InterruptedException {
        doReturn(Mono.error(new PaymentProviderTimeoutException(UUID.randomUUID())))
                .when(paymentProvider).authorize(any(), any());

        for (int i = 0; i < 4; i++) {
            authorize(paymentPendingOrder(), "TIMEOUT").expectStatus().isCreated();
        }

        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(Resilience4jConfig.PAYMENT_PROVIDER);
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        doReturn(Mono.just(PaymentAuthorizationResult.AUTHORIZED)).when(paymentProvider).authorize(any(), any());

        awaitOpenStateElapsed(circuitBreaker);

        for (int i = 0; i < 2; i++) {
            authorize(paymentPendingOrder(), "SUCCESS")
                    .expectStatus().isCreated()
                    .expectBody()
                    .jsonPath("$.status").isEqualTo("AUTHORIZED");
        }

        assertThat(circuitBreaker.getState())
                .as("permittedNumberOfCallsInHalfOpenState=2 successful calls must close the breaker again")
                .isEqualTo(CircuitBreaker.State.CLOSED);
    }

    private static void awaitOpenStateElapsed(CircuitBreaker circuitBreaker) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(2));
        while (circuitBreaker.getState() == CircuitBreaker.State.OPEN) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("Circuit breaker did not leave OPEN within 2s of waitDurationInOpenState "
                        + "elapsing (automaticTransitionFromOpenToHalfOpenEnabled=true)");
            }
            Thread.sleep(20);
        }
    }
}
