package com.ledgerflow.order.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.ledgerflow.order.application.OrderApplicationService;
import com.ledgerflow.order.domain.InvalidStateTransitionException;
import com.ledgerflow.order.domain.Order;
import com.ledgerflow.order.domain.OrderItem;
import com.ledgerflow.order.domain.OrderNotFoundException;
import com.ledgerflow.order.domain.OrderStatus;

import reactor.core.publisher.Mono;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Web-layer slice tests for {@link OrderController}.
 *
 * <p>{@link OrderApplicationService} is mocked at the application-service
 * boundary — the only collaborator the controller depends on — so these
 * tests exercise real reactive HTTP parsing, Bean Validation
 * ({@link org.springframework.web.bind.support.WebExchangeBindException} in
 * WebFlux, not MVC's {@code MethodArgumentNotValidException}) and the
 * module-scoped {@link OrderExceptionHandler}, which {@code @WebFluxTest}
 * picks up automatically as MVC/WebFlux infrastructure.
 */
@WebFluxTest(OrderController.class)
class OrderControllerTest {

    private static final String BASE_URL = "/api/v1/orders";

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private OrderApplicationService orderApplicationService;

    private static Order sampleOrder(UUID id, OrderStatus status) {
        return Order.rehydrate(
                id,
                "customer-1",
                "USD",
                new BigDecimal("19.98"),
                status,
                List.of(new OrderItem("sku-1", 2, new BigDecimal("9.99"))),
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z"));
    }

    private static final String VALID_CREATE_BODY = """
            {
              "customerId": "customer-1",
              "currency": "USD",
              "totalAmount": 19.98,
              "items": [
                { "productId": "sku-1", "quantity": 2, "unitPrice": 9.99 }
              ]
            }
            """;

    // ---------------------------------------------------------------
    // POST /api/v1/orders
    // ---------------------------------------------------------------

    @Test
    void create_shouldReturn201WithLocationAndBody_onSuccess() {
        UUID orderId = UUID.randomUUID();
        Order created = sampleOrder(orderId, OrderStatus.CREATED);
        when(orderApplicationService.create(eq("customer-1"), eq("USD"), eq(new BigDecimal("19.98")), anyList()))
                .thenReturn(Mono.just(created));

        webTestClient.post().uri(BASE_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(VALID_CREATE_BODY)
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().valueEquals("Location", BASE_URL + "/" + orderId)
                .expectBody()
                .jsonPath("$.id").isEqualTo(orderId.toString())
                .jsonPath("$.status").isEqualTo("CREATED")
                .jsonPath("$.customerId").isEqualTo("customer-1")
                .jsonPath("$.items[0].productId").isEqualTo("sku-1");
    }

    @Test
    void create_shouldReturn400_whenCustomerIdIsBlank() {
        String body = """
                {
                  "customerId": "",
                  "currency": "USD",
                  "totalAmount": 19.98,
                  "items": [
                    { "productId": "sku-1", "quantity": 2, "unitPrice": 9.99 }
                  ]
                }
                """;

        webTestClient.post().uri(BASE_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.title").isEqualTo("Validation failed");
    }

    @Test
    void create_shouldReturn400_whenCurrencyIsNotARealIsoCode() {
        String body = """
                {
                  "customerId": "customer-1",
                  "currency": "ZZZ",
                  "totalAmount": 19.98,
                  "items": [
                    { "productId": "sku-1", "quantity": 2, "unitPrice": 9.99 }
                  ]
                }
                """;

        webTestClient.post().uri(BASE_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.title").isEqualTo("Validation failed");
    }

    @Test
    void create_shouldReturn400_whenItemsIsEmpty() {
        String body = """
                {
                  "customerId": "customer-1",
                  "currency": "USD",
                  "totalAmount": 19.98,
                  "items": []
                }
                """;

        webTestClient.post().uri(BASE_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void create_shouldReturn400_whenDomainRejectsInvalidArgument() {
        when(orderApplicationService.create(anyString(), anyString(), any(BigDecimal.class), anyList()))
                .thenReturn(Mono.error(new IllegalArgumentException("currency is not a valid ISO 4217 code: ZZZ")));

        webTestClient.post().uri(BASE_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(VALID_CREATE_BODY)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.title").isEqualTo("Invalid order");
    }

    // ---------------------------------------------------------------
    // GET /api/v1/orders/{orderId}
    // ---------------------------------------------------------------

    @Test
    void get_shouldReturn200WithBody_whenOrderExists() {
        UUID orderId = UUID.randomUUID();
        when(orderApplicationService.get(orderId)).thenReturn(Mono.just(sampleOrder(orderId, OrderStatus.CREATED)));

        webTestClient.get().uri(BASE_URL + "/{orderId}", orderId)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo(orderId.toString())
                .jsonPath("$.status").isEqualTo("CREATED");
    }

    @Test
    void get_shouldReturn404_whenOrderDoesNotExist() {
        UUID orderId = UUID.randomUUID();
        when(orderApplicationService.get(orderId)).thenReturn(Mono.error(new OrderNotFoundException(orderId)));

        webTestClient.get().uri(BASE_URL + "/{orderId}", orderId)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.title").isEqualTo("Order not found");
    }

    // ---------------------------------------------------------------
    // POST /api/v1/orders/{orderId}/cancel
    // ---------------------------------------------------------------

    @Test
    void cancel_shouldReturn200WithCancelledOrder_onSuccess() {
        UUID orderId = UUID.randomUUID();
        when(orderApplicationService.cancel(orderId))
                .thenReturn(Mono.just(sampleOrder(orderId, OrderStatus.CANCELLED)));

        webTestClient.post().uri(BASE_URL + "/{orderId}/cancel", orderId)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("CANCELLED");

        verify(orderApplicationService).cancel(orderId);
    }

    @Test
    void cancel_shouldReturn404_whenOrderDoesNotExist() {
        UUID orderId = UUID.randomUUID();
        when(orderApplicationService.cancel(orderId)).thenReturn(Mono.error(new OrderNotFoundException(orderId)));

        webTestClient.post().uri(BASE_URL + "/{orderId}/cancel", orderId)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.title").isEqualTo("Order not found");
    }

    @Test
    void cancel_shouldReturn409_whenOrderIsAlreadyCancelled() {
        UUID orderId = UUID.randomUUID();
        when(orderApplicationService.cancel(orderId))
                .thenReturn(Mono.error(
                        new InvalidStateTransitionException(OrderStatus.CANCELLED, OrderStatus.CANCELLED)));

        webTestClient.post().uri(BASE_URL + "/{orderId}/cancel", orderId)
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.title").isEqualTo("Invalid order state transition");
    }
}
