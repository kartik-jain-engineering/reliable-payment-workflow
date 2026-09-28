package com.ledgerflow.order.service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.ledgerflow.order.model.InvalidStateTransitionException;
import com.ledgerflow.order.model.OrderStatus;
import com.ledgerflow.order.model.entity.OrderEntity;
import com.ledgerflow.order.model.entity.OrderItemEntity;
import com.ledgerflow.order.repo.OrderItemRepository;
import com.ledgerflow.order.repo.OrderRepository;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OrderService}, mocking the Spring Data
 * {@link OrderRepository} / {@link OrderItemRepository} at the persistence
 * boundary. The Bean Validation {@link Validator} is real, not mocked — it
 * has no I/O of its own, and using the real thing exercises the same
 * validation {@code OrderService} runs in production.
 */
class OrderServiceTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final OrderItemRepository orderItemRepository = mock(OrderItemRepository.class);
    private final OrderService service = new OrderService(orderRepository, orderItemRepository, validator);

    @BeforeAll
    static void setUpValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidatorFactory() {
        validatorFactory.close();
    }

    private static List<OrderItemEntity> oneItem() {
        return List.of(OrderItemEntity.builder()
                .productId("sku-1")
                .quantity(2)
                .unitPrice(new BigDecimal("9.99"))
                .build());
    }

    private static OrderEntity existingOrder(UUID id, OrderStatus status) {
        OffsetDateTime now = OffsetDateTime.now();
        return OrderEntity.builder()
                .id(id)
                .customerId("customer-1")
                .currency("USD")
                .totalAmount(BigDecimal.TEN)
                .statusCode(status.name())
                .version(3L)
                .createdAt(now)
                .updatedAt(now)
                .items(List.of())
                .build();
    }

    private static OrderItemEntity existingItem(UUID orderId) {
        return OrderItemEntity.builder()
                .id(UUID.randomUUID())
                .orderId(orderId)
                .productId("sku-1")
                .quantity(1)
                .unitPrice(BigDecimal.ONE)
                .build();
    }

    private void stubEchoSaves() {
        when(orderRepository.save(any(OrderEntity.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(orderItemRepository.save(any(OrderItemEntity.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(orderItemRepository.deleteByOrderId(any(UUID.class))).thenReturn(Mono.empty());
    }

    // ---------------------------------------------------------------
    // save (create)
    // ---------------------------------------------------------------

    @Test
    void save_shouldPersistTheOrderRow_thenReplaceItems_forANewOrder() {
        stubEchoSaves();
        OrderEntity order = OrderEntity.createNew("customer-1", "USD", BigDecimal.TEN, oneItem());

        StepVerifier.create(service.save(order))
                .assertNext(saved -> {
                    assertThat(saved.getId()).isEqualTo(order.getId());
                    assertThat(saved.getItems()).hasSize(1);
                })
                .verifyComplete();

        verify(orderRepository).save(order);
        verify(orderItemRepository).deleteByOrderId(order.getId());
        verify(orderItemRepository).save(any(OrderItemEntity.class));
    }

    @Test
    void save_shouldSignalConstraintViolationException_andNeverPersist_whenOrderIsInvalid() {
        OrderEntity invalid = OrderEntity.createNew(" ", "USD", BigDecimal.TEN, oneItem());

        StepVerifier.create(service.save(invalid))
                .expectError(ConstraintViolationException.class)
                .verify();

        verify(orderRepository, never()).save(any());
        verify(orderItemRepository, never()).save(any());
    }

    @Test
    void save_shouldSignalConstraintViolationException_whenCurrencyIsNotARealIsoCode() {
        OrderEntity invalid = OrderEntity.createNew("customer-1", "ZZZ", BigDecimal.TEN, oneItem());

        StepVerifier.create(service.save(invalid))
                .expectError(ConstraintViolationException.class)
                .verify();

        verify(orderRepository, never()).save(any());
    }

    // ---------------------------------------------------------------
    // get
    // ---------------------------------------------------------------

    @Test
    void get_shouldReturnOrderWithItems_whenRepositoryFindsIt() {
        UUID orderId = UUID.randomUUID();
        OrderEntity row = existingOrder(orderId, OrderStatus.CREATED);
        OrderItemEntity item = OrderItemEntity.builder()
                .id(UUID.randomUUID()).orderId(orderId).productId("sku-1").quantity(1).unitPrice(BigDecimal.ONE)
                .build();
        when(orderRepository.findById(orderId)).thenReturn(Mono.just(row));
        when(orderItemRepository.findAllByOrderId(orderId)).thenReturn(Flux.just(item));

        StepVerifier.create(service.get(orderId))
                .assertNext(found -> assertThat(found.getItems()).containsExactly(item))
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
        OrderEntity existing = existingOrder(orderId, OrderStatus.CREATED);
        when(orderRepository.findById(orderId)).thenReturn(Mono.just(existing));
        when(orderItemRepository.findAllByOrderId(orderId)).thenReturn(Flux.just(existingItem(orderId)));
        stubEchoSaves();

        StepVerifier.create(service.cancel(orderId))
                .expectNextMatches(order -> order.getStatus() == OrderStatus.CANCELLED)
                .verifyComplete();

        verify(orderRepository).save(eq(existing));
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
        OrderEntity alreadyCancelled = existingOrder(orderId, OrderStatus.CANCELLED);
        when(orderRepository.findById(orderId)).thenReturn(Mono.just(alreadyCancelled));
        when(orderItemRepository.findAllByOrderId(orderId)).thenReturn(Flux.empty());

        StepVerifier.create(service.cancel(orderId))
                .expectError(InvalidStateTransitionException.class)
                .verify();

        verify(orderRepository, never()).save(any());
    }
}
