package com.ledgerflow.order.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.ledgerflow.order.domain.InvalidStateTransitionException;
import com.ledgerflow.order.domain.Order;
import com.ledgerflow.order.domain.OrderItem;
import com.ledgerflow.order.domain.OrderNotFoundException;
import com.ledgerflow.order.domain.OrderRepository;
import com.ledgerflow.order.domain.OrderStatus;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OrderApplicationService}, mocking {@link OrderRepository}
 * at the persistence boundary — the only collaborator this service has. No
 * Spring context and no database: every behaviour here is pure composition
 * of the aggregate with the repository port.
 */
class OrderApplicationServiceTest {

    private static final List<OrderItem> ONE_ITEM =
            List.of(new OrderItem("sku-1", 2, new BigDecimal("9.99")));

    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final OrderApplicationService service = new OrderApplicationService(orderRepository);

    private static Order rehydrated(UUID id, OrderStatus status) {
        Instant now = Instant.now();
        return Order.rehydrate(id, "customer-1", "USD", BigDecimal.TEN, status, ONE_ITEM, now, now);
    }

    // ---------------------------------------------------------------
    // create
    // ---------------------------------------------------------------

    @Test
    void create_shouldSaveANewlyConstructedOrder_andReturnWhatTheRepositorySaved() {
        UUID savedId = UUID.randomUUID();
        Order saved = rehydrated(savedId, OrderStatus.CREATED);
        when(orderRepository.save(any(Order.class))).thenReturn(Mono.just(saved));

        StepVerifier.create(service.create("customer-1", "USD", BigDecimal.TEN, ONE_ITEM))
                .expectNext(saved)
                .verifyComplete();

        verify(orderRepository).save(any(Order.class));
    }

    /**
     * Verifies the {@code Mono.fromCallable} deferral in
     * {@link OrderApplicationService#create} — see its Javadoc for why.
     */
    @Test
    void create_shouldSignalError_ratherThanThrowSynchronously_whenDomainValidationFails() {
        Mono<Order> result = service.create("customer-1", "ZZZ", BigDecimal.TEN, ONE_ITEM);

        StepVerifier.create(result)
                .expectError(IllegalArgumentException.class)
                .verify();

        verify(orderRepository, never()).save(any());
    }

    @Test
    void create_shouldNotCallRepository_whenDomainValidationFails() {
        StepVerifier.create(service.create(null, "USD", BigDecimal.TEN, ONE_ITEM))
                .expectError(IllegalArgumentException.class)
                .verify();

        verify(orderRepository, never()).save(any());
    }

    // ---------------------------------------------------------------
    // get
    // ---------------------------------------------------------------

    @Test
    void get_shouldReturnOrder_whenRepositoryFindsIt() {
        UUID orderId = UUID.randomUUID();
        Order found = rehydrated(orderId, OrderStatus.CREATED);
        when(orderRepository.findById(orderId)).thenReturn(Mono.just(found));

        StepVerifier.create(service.get(orderId))
                .expectNext(found)
                .verifyComplete();
    }

    @Test
    void get_shouldSignalOrderNotFoundException_whenRepositoryCompletesEmpty() {
        UUID orderId = UUID.randomUUID();
        when(orderRepository.findById(orderId)).thenReturn(Mono.empty());

        StepVerifier.create(service.get(orderId))
                .expectErrorMatches(ex -> ex instanceof OrderNotFoundException
                        && ex.getMessage().contains(orderId.toString()))
                .verify();
    }

    // ---------------------------------------------------------------
    // cancel
    // ---------------------------------------------------------------

    @Test
    void cancel_shouldTransitionToCancelled_andPersistTheMutatedOrder() {
        UUID orderId = UUID.randomUUID();
        Order existing = rehydrated(orderId, OrderStatus.CREATED);
        when(orderRepository.findById(orderId)).thenReturn(Mono.just(existing));
        when(orderRepository.save(existing)).thenReturn(Mono.just(existing));

        StepVerifier.create(service.cancel(orderId))
                .expectNextMatches(order -> order.getStatus() == OrderStatus.CANCELLED)
                .verifyComplete();

        verify(orderRepository).save(existing);
    }

    @Test
    void cancel_shouldSignalOrderNotFoundException_andNeverCallSave_whenOrderDoesNotExist() {
        UUID orderId = UUID.randomUUID();
        when(orderRepository.findById(orderId)).thenReturn(Mono.empty());

        StepVerifier.create(service.cancel(orderId))
                .expectError(OrderNotFoundException.class)
                .verify();

        verify(orderRepository, never()).save(any());
    }

    @Test
    void cancel_shouldSignalInvalidStateTransitionException_andNeverCallSave_whenOrderAlreadyCancelled() {
        UUID orderId = UUID.randomUUID();
        Order alreadyCancelled = rehydrated(orderId, OrderStatus.CANCELLED);
        when(orderRepository.findById(orderId)).thenReturn(Mono.just(alreadyCancelled));

        StepVerifier.create(service.cancel(orderId))
                .expectError(InvalidStateTransitionException.class)
                .verify();

        verify(orderRepository, never()).save(any());
    }
}
