package com.ledgerflow.order.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.ConstraintViolationException;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.ledgerflow.config.SecurityConfig;
import com.ledgerflow.order.model.InvalidStateTransitionException;
import com.ledgerflow.order.model.OrderStatus;
import com.ledgerflow.order.model.entity.OrderEntity;
import com.ledgerflow.order.model.entity.OrderItemEntity;
import com.ledgerflow.order.service.OrderNotFoundException;
import com.ledgerflow.order.service.OrderService;

import reactor.core.publisher.Mono;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

/**
 * Web-layer slice tests for {@link OrderController}.
 *
 * <p>{@link OrderService} is mocked at the service boundary — the only
 * collaborator the controller depends on — so these tests exercise real
 * reactive HTTP parsing, Bean Validation
 * ({@link org.springframework.web.bind.support.WebExchangeBindException} in
 * WebFlux, not MVC's {@code MethodArgumentNotValidException}) and the
 * module-scoped {@link OrderExceptionHandler}, which {@code @WebFluxTest}
 * picks up automatically as MVC/WebFlux infrastructure.
 */
@WebFluxTest(OrderController.class)
@Import(SecurityConfig.class)
class OrderControllerTest {

    private static final String BASE_URL = "/api/v1/orders";
    private static final String CUSTOMER_ID = "customer-1";

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private OrderService orderService;

    @MockBean
    private ReactiveJwtDecoder jwtDecoder;

    private WebTestClient withScope(String scope) {
        return webTestClient.mutateWith(mockJwt()
                .jwt(jwt -> jwt.subject(CUSTOMER_ID))
                .authorities(new SimpleGrantedAuthority("SCOPE_" + scope)));
    }

    private static OrderEntity sampleOrder(UUID id, OrderStatus status) {
        OffsetDateTime timestamp = OffsetDateTime.parse("2026-01-01T00:00:00Z");
        return OrderEntity.builder()
                .id(id)
                .customerId("customer-1")
                .currency("USD")
                .totalAmount(new BigDecimal("19.98"))
                .statusCode(status.name())
                .version(0L)
                .createdAt(timestamp)
                .updatedAt(timestamp)
                .items(List.of(OrderItemEntity.builder()
                        .productId("sku-1").quantity(2).unitPrice(new BigDecimal("9.99")).build()))
                .build();
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
        OrderEntity created = sampleOrder(orderId, OrderStatus.CREATED);
        when(orderService.create(any(OrderEntity.class), eq(CUSTOMER_ID))).thenReturn(Mono.just(created));

        withScope("order:create").post().uri(BASE_URL)
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

        withScope("order:create").post().uri(BASE_URL)
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

        withScope("order:create").post().uri(BASE_URL)
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

        withScope("order:create").post().uri(BASE_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void create_shouldReturn400_whenServiceRejectsAConstraintViolation() {
        when(orderService.create(any(OrderEntity.class), eq(CUSTOMER_ID)))
                .thenReturn(Mono.error(new ConstraintViolationException(Set.of())));

        withScope("order:create").post().uri(BASE_URL)
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
        when(orderService.get(orderId, CUSTOMER_ID)).thenReturn(Mono.just(sampleOrder(orderId, OrderStatus.CREATED)));

        withScope("order:read").get().uri(BASE_URL + "/{orderId}", orderId)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo(orderId.toString())
                .jsonPath("$.status").isEqualTo("CREATED");
    }

    @Test
    void get_shouldReturn404_whenOrderDoesNotExist() {
        UUID orderId = UUID.randomUUID();
        when(orderService.get(orderId, CUSTOMER_ID)).thenReturn(Mono.error(new OrderNotFoundException(orderId)));

        withScope("order:read").get().uri(BASE_URL + "/{orderId}", orderId)
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
        when(orderService.cancel(orderId, CUSTOMER_ID))
                .thenReturn(Mono.just(sampleOrder(orderId, OrderStatus.CANCELLED)));

        withScope("order:cancel").post().uri(BASE_URL + "/{orderId}/cancel", orderId)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("CANCELLED");

        verify(orderService).cancel(orderId, CUSTOMER_ID);
    }

    @Test
    void cancel_shouldReturn404_whenOrderDoesNotExist() {
        UUID orderId = UUID.randomUUID();
        when(orderService.cancel(orderId, CUSTOMER_ID)).thenReturn(Mono.error(new OrderNotFoundException(orderId)));

        withScope("order:cancel").post().uri(BASE_URL + "/{orderId}/cancel", orderId)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.title").isEqualTo("Order not found");
    }

    @Test
    void cancel_shouldReturn409_whenOrderIsAlreadyCancelled() {
        UUID orderId = UUID.randomUUID();
        when(orderService.cancel(orderId, CUSTOMER_ID))
                .thenReturn(Mono.error(
                        new InvalidStateTransitionException(OrderStatus.CANCELLED, OrderStatus.CANCELLED)));

        withScope("order:cancel").post().uri(BASE_URL + "/{orderId}/cancel", orderId)
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.title").isEqualTo("Invalid order state transition");
    }

    // ---------------------------------------------------------------
    // Security: no token / malformed token / missing scope
    // ---------------------------------------------------------------

    @Test
    void create_shouldReturn401_whenNoToken() {
        webTestClient.post().uri(BASE_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(VALID_CREATE_BODY)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void get_shouldReturn401_whenNoToken() {
        webTestClient.get().uri(BASE_URL + "/{orderId}", UUID.randomUUID())
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void cancel_shouldReturn401_whenNoToken() {
        webTestClient.post().uri(BASE_URL + "/{orderId}/cancel", UUID.randomUUID())
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void create_shouldReturn401_whenBearerTokenIsMalformed() {
        when(jwtDecoder.decode(anyString())).thenReturn(Mono.error(new BadJwtException("malformed token")));

        webTestClient.post().uri(BASE_URL)
                .header("Authorization", "Bearer not-a-real-jwt")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(VALID_CREATE_BODY)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void create_shouldReturn403_whenScopeIsMissing() {
        withScope("order:read").post().uri(BASE_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(VALID_CREATE_BODY)
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void get_shouldReturn403_whenScopeIsMissing() {
        withScope("order:create").get().uri(BASE_URL + "/{orderId}", UUID.randomUUID())
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void cancel_shouldReturn403_whenScopeIsMissing() {
        withScope("order:read").post().uri(BASE_URL + "/{orderId}/cancel", UUID.randomUUID())
                .exchange()
                .expectStatus().isForbidden();
    }
}

