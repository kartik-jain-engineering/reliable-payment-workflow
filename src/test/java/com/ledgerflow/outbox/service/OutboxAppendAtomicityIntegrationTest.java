package com.ledgerflow.outbox.service;

import java.math.BigDecimal;
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
import com.ledgerflow.order.service.OrderService;
import com.ledgerflow.outbox.model.event.OrderCreatedEvent;
import com.ledgerflow.outbox.model.event.PaymentAuthorizedEvent;
import com.ledgerflow.outbox.model.event.PaymentDeclinedEvent;
import com.ledgerflow.outbox.repo.OutboxEventRepository;
import com.ledgerflow.payment.model.SimulatedOutcome;
import com.ledgerflow.payment.model.entity.PaymentEntity;
import com.ledgerflow.payment.service.PaymentService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the business write and its outbox row are appended atomically and
 * exactly once, against a real PostgreSQL instance, following the same
 * Testcontainers {@code @ServiceConnection} pattern as
 * {@code OrderServiceIntegrationTest}. Assertions are scoped to the
 * aggregate id created by each test, so tests stay isolated without needing
 * table cleanup between them.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class OutboxAppendAtomicityIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private OrderService orderService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    private static OrderEntity newOrder() {
        List<OrderItemEntity> items = List.of(
                OrderItemEntity.builder().productId("sku-1").quantity(1).unitPrice(new BigDecimal("19.98")).build());
        return OrderEntity.createNew("customer-1", "USD", new BigDecimal("19.98"), items);
    }

    private OrderEntity paymentPendingOrder() {
        OrderEntity saved = orderService.save(newOrder()).block();
        OrderEntity fetched = orderService.get(saved.getId()).block();
        fetched.transitionTo(OrderStatus.PAYMENT_PENDING);
        return orderService.save(fetched).block();
    }

    private long eventCountFor(UUID aggregateId, String eventType) {
        return outboxEventRepository.findAll()
                .filter(e -> e.getAggregateId().equals(aggregateId) && e.getEventType().equals(eventType))
                .count()
                .block();
    }

    private long totalEventCountFor(UUID aggregateId) {
        return outboxEventRepository.findAll()
                .filter(e -> e.getAggregateId().equals(aggregateId))
                .count()
                .block();
    }

    @Test
    void save_shouldAppendExactlyOneOrderCreatedRow_forANewOrder() {
        OrderEntity saved = orderService.save(newOrder()).block();

        assertThat(eventCountFor(saved.getId(), OrderCreatedEvent.EVENT_TYPE)).isEqualTo(1);
    }

    @Test
    void cancel_shouldNotAppendAnotherOrderCreatedRow_forAnExistingOrder() {
        OrderEntity saved = orderService.save(newOrder()).block();
        assertThat(totalEventCountFor(saved.getId())).isEqualTo(1);

        orderService.cancel(saved.getId()).block();

        assertThat(totalEventCountFor(saved.getId()))
                .as("cancelling an already-persisted order must not append a second OrderCreated row")
                .isEqualTo(1);
    }

    @Test
    void authorize_shouldAppendPaymentAuthorizedRow_whenProviderAuthorizes() {
        OrderEntity order = paymentPendingOrder();

        PaymentEntity payment = paymentService.authorize(order.getId(), SimulatedOutcome.SUCCESS).block();

        assertThat(eventCountFor(payment.getId(), PaymentAuthorizedEvent.EVENT_TYPE)).isEqualTo(1);
        assertThat(eventCountFor(payment.getId(), PaymentDeclinedEvent.EVENT_TYPE)).isZero();
    }

    @Test
    void authorize_shouldAppendPaymentDeclinedRow_whenProviderDeclines() {
        OrderEntity order = paymentPendingOrder();

        PaymentEntity payment = paymentService.authorize(order.getId(), SimulatedOutcome.DECLINE).block();

        assertThat(eventCountFor(payment.getId(), PaymentDeclinedEvent.EVENT_TYPE)).isEqualTo(1);
        assertThat(eventCountFor(payment.getId(), PaymentAuthorizedEvent.EVENT_TYPE)).isZero();
    }
}
