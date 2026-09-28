package com.ledgerflow.order.service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.ledgerflow.order.model.OrderStatus;
import com.ledgerflow.order.model.entity.OrderEntity;
import com.ledgerflow.order.model.entity.OrderItemEntity;
import com.ledgerflow.order.repo.OrderRepository;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link OrderService} against a real PostgreSQL
 * instance, following the same Testcontainers {@code @ServiceConnection}
 * pattern as {@code com.ledgerflow.LedgerflowApplicationTests} — the
 * container contributes both the {@code R2dbcConnectionDetails} the app
 * uses and the {@code JdbcConnectionDetails} Flyway needs.
 *
 * <p>{@link OrderEntity} has no {@code equals}/{@code hashCode}, so every
 * assertion below compares fields explicitly; {@link BigDecimal} fields are
 * compared with {@code isEqualByComparingTo} since the database may return a
 * different (but numerically equal) scale.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class OrderServiceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderRepository orderRepository;

    private static OrderEntity newOrder() {
        List<OrderItemEntity> items = List.of(
                OrderItemEntity.builder().productId("sku-1").quantity(3).unitPrice(new BigDecimal("9.99")).build(),
                OrderItemEntity.builder().productId("sku-2").quantity(1).unitPrice(BigDecimal.ZERO).build());
        return OrderEntity.createNew("customer-1", "USD", new BigDecimal("29.97"), items);
    }

    private static OffsetDateTime roundToMicros(OffsetDateTime value) {
        return value.plusNanos(500).truncatedTo(ChronoUnit.MICROS);
    }

    @Test
    void save_thenGet_shouldRoundTripEveryField() {
        OrderEntity order = newOrder();

        StepVerifier.create(orderService.save(order)
                        .then(orderService.get(order.getId())))
                .assertNext(actual -> {
                    assertThat(actual.getId()).isEqualTo(order.getId());
                    assertThat(actual.getCustomerId()).isEqualTo(order.getCustomerId());
                    assertThat(actual.getCurrency()).isEqualTo(order.getCurrency());
                    assertThat(actual.getTotalAmount()).isEqualByComparingTo(order.getTotalAmount());
                    assertThat(actual.getStatus()).isEqualTo(order.getStatus());
                    // Postgres timestamptz stores microsecond precision and rounds (not
                    // truncates) sub-microsecond nanos, so round the expected value the
                    // same way before comparing.
                    assertThat(actual.getCreatedAt()).isEqualTo(roundToMicros(order.getCreatedAt()));
                    assertThat(actual.getUpdatedAt()).isEqualTo(roundToMicros(order.getUpdatedAt()));

                    assertThat(actual.getItems()).hasSameSizeAs(order.getItems());
                    for (int i = 0; i < order.getItems().size(); i++) {
                        OrderItemEntity expectedItem = order.getItems().get(i);
                        OrderItemEntity actualItem = actual.getItems().get(i);
                        assertThat(actualItem.getProductId()).isEqualTo(expectedItem.getProductId());
                        assertThat(actualItem.getQuantity()).isEqualTo(expectedItem.getQuantity());
                        assertThat(actualItem.getUnitPrice()).isEqualByComparingTo(expectedItem.getUnitPrice());
                    }
                })
                .verifyComplete();
    }

    @Test
    void get_shouldSignalOrderNotFoundException_whenOrderDoesNotExist() {
        StepVerifier.create(orderService.get(UUID.randomUUID()))
                .expectError(OrderNotFoundException.class)
                .verify();
    }

    @Test
    void cancel_shouldPersistStatusChange_afterReload() {
        OrderEntity order = newOrder();

        StepVerifier.create(orderService.save(order)
                        .then(Mono.defer(() -> orderService.cancel(order.getId())))
                        .then(orderService.get(order.getId())))
                .assertNext(reloaded -> assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.CANCELLED))
                .verifyComplete();
    }

    @Test
    void cancel_shouldIncrementVersion_andPreserveCreatedAt() {
        OrderEntity order = newOrder();
        orderService.save(order).block();

        OrderEntity beforeCancel = orderRepository.findById(order.getId()).block();

        orderService.cancel(order.getId()).block();

        OrderEntity afterCancel = orderRepository.findById(order.getId()).block();

        assertThat(afterCancel.getVersion()).isGreaterThan(beforeCancel.getVersion());
        assertThat(afterCancel.getCreatedAt()).isEqualTo(beforeCancel.getCreatedAt());
    }
}
