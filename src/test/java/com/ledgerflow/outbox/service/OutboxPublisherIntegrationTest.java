package com.ledgerflow.outbox.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.ledgerflow.order.model.entity.OrderEntity;
import com.ledgerflow.order.model.entity.OrderItemEntity;
import com.ledgerflow.order.service.OrderService;
import com.ledgerflow.outbox.model.OutboxEventStatus;
import com.ledgerflow.outbox.model.entity.OutboxEventEntity;
import com.ledgerflow.outbox.repo.OutboxConsumedEventRepository;
import com.ledgerflow.outbox.repo.OutboxEventReceiptRepository;
import com.ledgerflow.outbox.repo.OutboxEventRepository;
import com.ledgerflow.outbox.service.handler.OrderCreatedReceiptHandler;

import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;

/**
 * Behavioral tests for the outbox publish/dispatch/claim/retry path against a
 * real PostgreSQL instance, following the same Testcontainers
 * {@code @ServiceConnection} pattern as {@code OrderServiceIntegrationTest}
 * and {@code PaymentIdempotencyTest} — the unique constraint on
 * {@code (handler_name, event_id)} that decides whether a handler has
 * already consumed an event only means something against a real database.
 *
 * <p>The scheduler is disabled in the {@code test} profile, so every test
 * drives polling deterministically by calling
 * {@link OutboxPublisher#publishPendingBatch()} directly.
 * {@code max-attempts} is lowered to 2 so failure-path tests don't need a
 * long chain of polls. {@link OrderCreatedReceiptHandler} is spied on (not
 * mocked) so the real {@link OutboxEventDispatcher} claim/handle transaction
 * still runs, while individual invocations can be forced to fail.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@TestPropertySource(properties = "ledgerflow.outbox.publisher.max-attempts=2")
class OutboxPublisherIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private OutboxPublisher outboxPublisher;

    @Autowired
    private OutboxEventDispatcher dispatcher;

    @SpyBean
    private OutboxService outboxService;

    @Autowired
    private OrderService orderService;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private OutboxConsumedEventRepository consumedEventRepository;

    @Autowired
    private OutboxEventReceiptRepository receiptRepository;

    @SpyBean
    private OrderCreatedReceiptHandler orderCreatedHandler;

    @BeforeEach
    void cleanOutboxTables() {
        consumedEventRepository.deleteAll()
                .then(receiptRepository.deleteAll())
                .then(outboxEventRepository.deleteAll())
                .block();
    }

    private static OrderEntity newOrder() {
        List<OrderItemEntity> items = List.of(
                OrderItemEntity.builder().productId("sku-1").quantity(1).unitPrice(new BigDecimal("9.99")).build());
        return OrderEntity.createNew("customer-1", "USD", new BigDecimal("9.99"), items);
    }

    private OutboxEventEntity createPendingOrderCreatedEvent() {
        OrderEntity saved = orderService.save(newOrder()).block();
        return outboxEventRepository.findAll()
                .filter(e -> e.getAggregateId().equals(saved.getId()))
                .blockFirst();
    }

    private long receiptCountFor(UUID eventId) {
        return receiptRepository.findAll()
                .filter(r -> r.getEventId().equals(eventId))
                .count()
                .block();
    }

    private long consumedCountFor(UUID eventId) {
        return consumedEventRepository.findAll()
                .filter(c -> c.getEventId().equals(eventId))
                .count()
                .block();
    }

    // ---------------------------------------------------------------
    // Duplicate delivery
    // ---------------------------------------------------------------

    @Test
    void dispatch_shouldRecordReceiptExactlyOnce_whenSameEventDispatchedTwiceDirectly() {
        OutboxEventEntity event = createPendingOrderCreatedEvent();

        dispatcher.dispatch(event).block();
        assertThat(receiptCountFor(event.getId())).isEqualTo(1);
        assertThat(consumedCountFor(event.getId())).isEqualTo(1);

        dispatcher.dispatch(event).block();
        assertThat(receiptCountFor(event.getId())).isEqualTo(1);
        assertThat(consumedCountFor(event.getId())).isEqualTo(1);
    }

    @Test
    void publishPendingBatch_shouldNotDuplicateReceipt_whenRowResetToPendingAndPolledAgain() {
        OutboxEventEntity event = createPendingOrderCreatedEvent();
        UUID eventId = event.getId();

        outboxPublisher.publishPendingBatch().block();

        OutboxEventEntity publishedOnce = outboxEventRepository.findById(eventId).block();
        assertThat(publishedOnce.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
        assertThat(publishedOnce.getPublishedAt()).isNotNull();
        assertThat(receiptCountFor(eventId)).isEqualTo(1);
        assertThat(consumedCountFor(eventId)).isEqualTo(1);

        publishedOnce.setStatus(OutboxEventStatus.PENDING);
        publishedOnce.setPublishedAt(null);
        outboxEventRepository.save(publishedOnce).block();

        outboxPublisher.publishPendingBatch().block();

        OutboxEventEntity publishedAgain = outboxEventRepository.findById(eventId).block();
        assertThat(publishedAgain.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
        assertThat(publishedAgain.getPublishedAt()).isNotNull();
        assertThat(receiptCountFor(eventId)).isEqualTo(1);
        assertThat(consumedCountFor(eventId)).isEqualTo(1);
    }

    // ---------------------------------------------------------------
    // Restart: claim + handler effect committed, outbox row still PENDING
    // ---------------------------------------------------------------

    @Test
    void publishPendingBatch_shouldPickUpRowLeftPendingAfterDispatch_withoutReRunningHandler() {
        OutboxEventEntity event = createPendingOrderCreatedEvent();
        UUID eventId = event.getId();

        dispatcher.dispatch(event).block();

        OutboxEventEntity stillPending = outboxEventRepository.findById(eventId).block();
        assertThat(stillPending.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(receiptCountFor(eventId)).isEqualTo(1);
        assertThat(consumedCountFor(eventId)).isEqualTo(1);

        outboxPublisher.publishPendingBatch().block();

        OutboxEventEntity afterRestart = outboxEventRepository.findById(eventId).block();
        assertThat(afterRestart.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
        assertThat(afterRestart.getPublishedAt()).isNotNull();
        assertThat(receiptCountFor(eventId)).isEqualTo(1);
        assertThat(consumedCountFor(eventId)).isEqualTo(1);
    }

    @Test
    void publishPendingBatch_shouldPublishPendingRow_withAttemptsLeftFromAPreviousFailedPoll() {
        OutboxEventEntity event = createPendingOrderCreatedEvent();
        UUID eventId = event.getId();

        outboxService.recordFailedAttempt(eventId, "transient failure", 5).block();

        OutboxEventEntity afterFailedAttempt = outboxEventRepository.findById(eventId).block();
        assertThat(afterFailedAttempt.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(afterFailedAttempt.getAttempts()).isEqualTo(1);

        outboxPublisher.publishPendingBatch().block();

        OutboxEventEntity afterPoll = outboxEventRepository.findById(eventId).block();
        assertThat(afterPoll.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
        assertThat(afterPoll.getPublishedAt()).isNotNull();
        assertThat(receiptCountFor(eventId)).isEqualTo(1);
    }

    // ---------------------------------------------------------------
    // Publisher failure
    // ---------------------------------------------------------------

    @Test
    void dispatch_shouldRollBackClaim_whenHandlerFails_soRetrySucceedsWithExactlyOneReceipt() {
        OutboxEventEntity event = createPendingOrderCreatedEvent();
        UUID eventId = event.getId();

        doAnswer(invocation -> Mono.error(new RuntimeException("transient boom")))
                .when(orderCreatedHandler).handle(any());

        outboxPublisher.publishPendingBatch().block();

        OutboxEventEntity afterFirstPoll = outboxEventRepository.findById(eventId).block();
        assertThat(afterFirstPoll.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(afterFirstPoll.getAttempts()).isEqualTo(1);
        assertThat(receiptCountFor(eventId)).isZero();
        assertThat(consumedCountFor(eventId))
                .as("the claim must roll back together with the failed handler effect")
                .isZero();

        doAnswer(InvocationOnMock::callRealMethod).when(orderCreatedHandler).handle(any());

        outboxPublisher.publishPendingBatch().block();

        OutboxEventEntity afterSecondPoll = outboxEventRepository.findById(eventId).block();
        assertThat(afterSecondPoll.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
        assertThat(afterSecondPoll.getPublishedAt()).isNotNull();
        assertThat(receiptCountFor(eventId)).isEqualTo(1);
        assertThat(consumedCountFor(eventId)).isEqualTo(1);
    }

    @Test
    void publishPendingBatch_shouldMarkFailedAfterMaxAttempts_andStopRetrying_whileOtherEventInBatchStillPublishes() {
        OutboxEventEntity failingEvent = createPendingOrderCreatedEvent();
        OutboxEventEntity healthyEvent = createPendingOrderCreatedEvent();
        UUID failingId = failingEvent.getId();
        UUID healthyId = healthyEvent.getId();

        doAnswer(invocation -> {
            OutboxEventEntity event = invocation.getArgument(0);
            if (event.getId().equals(failingId)) {
                return Mono.error(new RuntimeException("boom"));
            }
            return invocation.callRealMethod();
        }).when(orderCreatedHandler).handle(any());

        outboxPublisher.publishPendingBatch().block();

        OutboxEventEntity failingAfterPoll1 = outboxEventRepository.findById(failingId).block();
        assertThat(failingAfterPoll1.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(failingAfterPoll1.getAttempts()).isEqualTo(1);

        OutboxEventEntity healthyAfterPoll1 = outboxEventRepository.findById(healthyId).block();
        assertThat(healthyAfterPoll1.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
        assertThat(receiptCountFor(healthyId))
                .as("one failing event must not block another event in the same batch")
                .isEqualTo(1);

        outboxPublisher.publishPendingBatch().block();

        OutboxEventEntity failingAfterPoll2 = outboxEventRepository.findById(failingId).block();
        assertThat(failingAfterPoll2.getStatus()).isEqualTo(OutboxEventStatus.FAILED);
        assertThat(failingAfterPoll2.getAttempts()).isEqualTo(2);
        assertThat(failingAfterPoll2.getLastError()).contains("boom");
        assertThat(receiptCountFor(failingId)).isZero();
        assertThat(consumedCountFor(failingId)).isZero();

        outboxPublisher.publishPendingBatch().block();

        OutboxEventEntity failingAfterPoll3 = outboxEventRepository.findById(failingId).block();
        assertThat(failingAfterPoll3.getStatus())
                .as("FAILED rows must not be retried by later polls")
                .isEqualTo(OutboxEventStatus.FAILED);
        assertThat(failingAfterPoll3.getAttempts()).isEqualTo(2);
        assertThat(receiptCountFor(failingId)).isZero();
    }

    @Test
    void publishPendingBatch_shouldLeaveRowPendingWithoutCountingAttempt_whenMarkPublishedFailsAfterSuccessfulDispatch() {
        OutboxEventEntity event = createPendingOrderCreatedEvent();
        UUID eventId = event.getId();

        Mono<OutboxEventEntity> markFailure = Mono.error(new RuntimeException("mark boom"));
        doReturn(markFailure).doReturn(markFailure).doCallRealMethod()
                .when(outboxService).markPublished(eventId);

        outboxPublisher.publishPendingBatch().block();

        OutboxEventEntity afterPoll1 = outboxEventRepository.findById(eventId).block();
        assertThat(afterPoll1.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(afterPoll1.getAttempts())
                .as("a failed mark after a successful dispatch must not count as a publish attempt")
                .isZero();
        assertThat(afterPoll1.getLastError()).isNull();
        assertThat(receiptCountFor(eventId)).isEqualTo(1);
        assertThat(consumedCountFor(eventId)).isEqualTo(1);

        outboxPublisher.publishPendingBatch().block();

        OutboxEventEntity afterPoll2 = outboxEventRepository.findById(eventId).block();
        assertThat(afterPoll2.getStatus())
                .as("max-attempts consecutive mark failures must not mark a dispatched event FAILED")
                .isEqualTo(OutboxEventStatus.PENDING);
        assertThat(afterPoll2.getAttempts()).isZero();
        assertThat(afterPoll2.getLastError()).isNull();
        assertThat(receiptCountFor(eventId)).isEqualTo(1);
        assertThat(consumedCountFor(eventId)).isEqualTo(1);

        outboxPublisher.publishPendingBatch().block();

        OutboxEventEntity afterPoll3 = outboxEventRepository.findById(eventId).block();
        assertThat(afterPoll3.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
        assertThat(afterPoll3.getPublishedAt()).isNotNull();
        assertThat(afterPoll3.getAttempts()).isZero();
        assertThat(receiptCountFor(eventId)).isEqualTo(1);
        assertThat(consumedCountFor(eventId)).isEqualTo(1);
    }
}
