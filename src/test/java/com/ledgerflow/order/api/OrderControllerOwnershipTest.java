package com.ledgerflow.order.api;

import java.math.BigDecimal;
import java.util.List;

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

import com.ledgerflow.order.model.entity.OrderEntity;
import com.ledgerflow.order.model.entity.OrderItemEntity;
import com.ledgerflow.order.service.OrderService;

import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

/**
 * Full-stack resource-ownership tests for {@link OrderController}, run against
 * a real PostgreSQL instance so ownership is proven with actually persisted
 * orders rather than a mocked {@link OrderService}, following the same
 * Testcontainers {@code @ServiceConnection} pattern as
 * {@code com.ledgerflow.payment.api.PaymentControllerTest}.
 */
@SpringBootTest
@AutoConfigureWebTestClient
@Testcontainers
@ActiveProfiles("test")
class OrderControllerOwnershipTest {

    private static final String BASE_URL = "/api/v1/orders";
    private static final String OWNER = "customer-1";
    private static final String OTHER_CUSTOMER = "customer-2";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private OrderService orderService;

    private WebTestClient asSubject(String subject, String scope) {
        return webTestClient.mutateWith(mockJwt()
                .jwt(jwt -> jwt.subject(subject))
                .authorities(new SimpleGrantedAuthority("SCOPE_" + scope)));
    }

    private OrderEntity persistOrderFor(String customerId) {
        List<OrderItemEntity> items = List.of(
                OrderItemEntity.builder().productId("sku-1").quantity(1).unitPrice(new BigDecimal("19.98")).build());
        OrderEntity order = OrderEntity.createNew(customerId, "USD", new BigDecimal("19.98"), items);
        return orderService.save(order).block();
    }

    // ---------------------------------------------------------------
    // POST /api/v1/orders — order-create ownership rule
    // ---------------------------------------------------------------

    @Test
    void create_shouldReturn403_whenRequestBodyCustomerIdDoesNotMatchAuthenticatedSubject() {
        String body = """
                {
                  "customerId": "%s",
                  "currency": "USD",
                  "totalAmount": 19.98,
                  "items": [
                    { "productId": "sku-1", "quantity": 1, "unitPrice": 19.98 }
                  ]
                }
                """.formatted(OTHER_CUSTOMER);

        asSubject(OWNER, "order:create").post().uri(BASE_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.title").isEqualTo("Access denied");
    }

    @Test
    void create_shouldReturn201_whenRequestBodyCustomerIdMatchesAuthenticatedSubject() {
        String body = """
                {
                  "customerId": "%s",
                  "currency": "USD",
                  "totalAmount": 19.98,
                  "items": [
                    { "productId": "sku-1", "quantity": 1, "unitPrice": 19.98 }
                  ]
                }
                """.formatted(OWNER);

        asSubject(OWNER, "order:create").post().uri(BASE_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.customerId").isEqualTo(OWNER);
    }

    // ---------------------------------------------------------------
    // GET /api/v1/orders/{orderId}
    // ---------------------------------------------------------------

    @Test
    void get_shouldReturn403_whenOrderBelongsToAnotherCustomer() {
        OrderEntity order = persistOrderFor(OWNER);

        asSubject(OTHER_CUSTOMER, "order:read").get().uri(BASE_URL + "/{orderId}", order.getId())
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.title").isEqualTo("Access denied");
    }

    @Test
    void get_shouldReturn200_whenOrderBelongsToTheAuthenticatedCustomer() {
        OrderEntity order = persistOrderFor(OWNER);

        asSubject(OWNER, "order:read").get().uri(BASE_URL + "/{orderId}", order.getId())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo(order.getId().toString());
    }

    // ---------------------------------------------------------------
    // POST /api/v1/orders/{orderId}/cancel
    // ---------------------------------------------------------------

    @Test
    void cancel_shouldReturn403_whenOrderBelongsToAnotherCustomer() {
        OrderEntity order = persistOrderFor(OWNER);

        asSubject(OTHER_CUSTOMER, "order:cancel").post().uri(BASE_URL + "/{orderId}/cancel", order.getId())
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.title").isEqualTo("Access denied");
    }

    @Test
    void cancel_shouldReturn200_whenOrderBelongsToTheAuthenticatedCustomer() {
        OrderEntity order = persistOrderFor(OWNER);

        asSubject(OWNER, "order:cancel").post().uri(BASE_URL + "/{orderId}/cancel", order.getId())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("CANCELLED");
    }
}
