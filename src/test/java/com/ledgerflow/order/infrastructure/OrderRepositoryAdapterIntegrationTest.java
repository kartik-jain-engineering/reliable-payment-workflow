package com.ledgerflow.order.infrastructure;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.ledgerflow.order.domain.Order;
import com.ledgerflow.order.domain.OrderItem;
import com.ledgerflow.order.domain.OrderRepository;
import com.ledgerflow.order.domain.OrderStatus;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.data.relational.core.query.Criteria.where;
import static org.springframework.data.relational.core.query.Query.query;

/**
 * Integration tests for {@link OrderRepositoryAdapter} against a real
 * PostgreSQL instance, following the same Testcontainers
 * {@code @ServiceConnection} pattern as
 * {@code com.ledgerflow.LedgerflowApplicationTests} — the container
 * contributes both the {@code R2dbcConnectionDetails} the app uses and the
 * {@code JdbcConnectionDetails} Flyway needs.
 *
 * <p>Lives in {@code com.ledgerflow.order.infrastructure} so it can see the
 * package-private {@link OrderRepositoryAdapter} and {@link OrderRow}, the
 * latter used here to inspect the persistence-only {@code @Version} field
 * that the domain aggregate never carries.
 *
 * <p>{@link Order} deliberately has no {@code equals}/{@code hashCode}, so
 * every assertion below compares fields explicitly; {@link BigDecimal}
 * fields are compared with {@code isEqualByComparingTo} since the database
 * may return a different (but numerically equal) scale.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class OrderRepositoryAdapterIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private R2dbcEntityTemplate template;

    private static Order newOrder() {
        return Order.create(
                "customer-1",
                "USD",
                new BigDecimal("29.97"),
                List.of(
                        new OrderItem("sku-1", 3, new BigDecimal("9.99")),
                        new OrderItem("sku-2", 1, BigDecimal.ZERO)));
    }

    private OrderRow loadRow(UUID orderId) {
        return template.selectOne(query(where("id").is(orderId)), OrderRow.class).block();
    }

    @Test
    void save_thenFindById_shouldRoundTripEveryField() {
        Order order = newOrder();

        StepVerifier.create(orderRepository.save(order)
                        .then(orderRepository.findById(order.getId())))
                .assertNext(actual -> {
                    assertThat(actual.getId()).isEqualTo(order.getId());
                    assertThat(actual.getCustomerId()).isEqualTo(order.getCustomerId());
                    assertThat(actual.getCurrency()).isEqualTo(order.getCurrency());
                    assertThat(actual.getTotalAmount()).isEqualByComparingTo(order.getTotalAmount());
                    assertThat(actual.getStatus()).isEqualTo(order.getStatus());
                    assertThat(actual.getCreatedAt()).isEqualTo(order.getCreatedAt());
                    assertThat(actual.getUpdatedAt()).isEqualTo(order.getUpdatedAt());

                    assertThat(actual.getItems()).hasSameSizeAs(order.getItems());
                    for (int i = 0; i < order.getItems().size(); i++) {
                        OrderItem expectedItem = order.getItems().get(i);
                        OrderItem actualItem = actual.getItems().get(i);
                        assertThat(actualItem.productId()).isEqualTo(expectedItem.productId());
                        assertThat(actualItem.quantity()).isEqualTo(expectedItem.quantity());
                        assertThat(actualItem.unitPrice()).isEqualByComparingTo(expectedItem.unitPrice());
                    }
                })
                .verifyComplete();
    }

    @Test
    void findById_shouldCompleteEmpty_whenOrderDoesNotExist() {
        StepVerifier.create(orderRepository.findById(UUID.randomUUID()))
                .verifyComplete();
    }

    @Test
    void save_shouldPersistStatusChange_afterCancelAndReload() {
        Order order = newOrder();

        // The cancel() mutation and the follow-up save must be deferred inside
        // Mono.defer: Java evaluates method arguments eagerly, so calling
        // order.cancel() outside a deferred publisher would flip the in-memory
        // status to CANCELLED before the *first* save (which must persist
        // CREATED) even subscribes.
        StepVerifier.create(orderRepository.save(order)
                        .then(Mono.defer(() -> {
                            order.cancel();
                            return orderRepository.save(order);
                        }))
                        .then(orderRepository.findById(order.getId())))
                .assertNext(reloaded -> assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.CANCELLED))
                .verifyComplete();
    }

    @Test
    void save_shouldIncrementVersion_andPreserveCreatedAt_onUpdate() {
        Order order = newOrder();
        orderRepository.save(order).block();

        OrderRow beforeUpdateRow = loadRow(order.getId());

        order.cancel();
        orderRepository.save(order).block();

        OrderRow afterUpdateRow = loadRow(order.getId());

        assertThat(afterUpdateRow.getVersion()).isGreaterThan(beforeUpdateRow.getVersion());
        assertThat(afterUpdateRow.toDomain(List.of()).getCreatedAt())
                .isEqualTo(beforeUpdateRow.toDomain(List.of()).getCreatedAt());
    }
}
