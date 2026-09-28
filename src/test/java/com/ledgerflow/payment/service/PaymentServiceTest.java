package com.ledgerflow.payment.service;

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
import org.mockito.ArgumentCaptor;

import com.ledgerflow.order.model.OrderStatus;
import com.ledgerflow.order.model.entity.OrderEntity;
import com.ledgerflow.order.service.OrderService;
import com.ledgerflow.outbox.service.OutboxService;
import com.ledgerflow.payment.model.PaymentStatus;
import com.ledgerflow.payment.model.SimulatedOutcome;
import com.ledgerflow.payment.model.entity.PaymentEntity;
import com.ledgerflow.payment.provider.PaymentAuthorizationResult;
import com.ledgerflow.payment.provider.PaymentProvider;
import com.ledgerflow.payment.provider.PaymentProviderTimeoutException;
import com.ledgerflow.payment.repo.PaymentRepository;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PaymentService}, mocking the Spring Data
 * {@link PaymentRepository}, the {@link OrderService} collaborator and the
 * {@link PaymentProvider} at their respective boundaries. The Bean
 * Validation {@link Validator} is real — same rationale as
 * {@code OrderServiceTest}.
 */
class PaymentServiceTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
    private final OrderService orderService = mock(OrderService.class);
    private final PaymentProvider paymentProvider = mock(PaymentProvider.class);
    private final OutboxService outboxService = mock(OutboxService.class);
    private final PaymentService service =
            new PaymentService(paymentRepository, orderService, paymentProvider, outboxService, validator);

    @BeforeAll
    static void setUpValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidatorFactory() {
        validatorFactory.close();
    }

    private static OrderEntity existingOrder(UUID id, OrderStatus status) {
        OffsetDateTime now = OffsetDateTime.now();
        return OrderEntity.builder()
                .id(id)
                .customerId("customer-1")
                .currency("USD")
                .totalAmount(new BigDecimal("19.98"))
                .statusCode(status.name())
                .version(3L)
                .createdAt(now)
                .updatedAt(now)
                .items(List.of())
                .build();
    }

    private void stubEchoPaymentSaves() {
        when(paymentRepository.save(any(PaymentEntity.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
    }

    private void stubEchoOrderSaves() {
        when(orderService.save(any(OrderEntity.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(outboxService.append(any())).thenReturn(Mono.empty());
    }

    // ---------------------------------------------------------------
    // authorize - success (provider authorizes)
    // ---------------------------------------------------------------

    @Test
    void authorize_shouldReturnAuthorizedPayment_andConfirmOrder_whenProviderAuthorizes() {
        UUID orderId = UUID.randomUUID();
        OrderEntity order = existingOrder(orderId, OrderStatus.PAYMENT_PENDING);
        when(orderService.get(orderId)).thenReturn(Mono.just(order));
        when(paymentProvider.authorize(any(PaymentEntity.class), any()))
                .thenReturn(Mono.just(PaymentAuthorizationResult.AUTHORIZED));
        stubEchoPaymentSaves();
        stubEchoOrderSaves();

        StepVerifier.create(service.authorize(orderId, SimulatedOutcome.SUCCESS))
                .assertNext(payment -> {
                    assertThat(payment.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
                    assertThat(payment.getOrderId()).isEqualTo(orderId);
                })
                .verifyComplete();

        ArgumentCaptor<OrderEntity> savedOrder = ArgumentCaptor.forClass(OrderEntity.class);
        verify(orderService).save(savedOrder.capture());
        assertThat(savedOrder.getValue().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    }

    // ---------------------------------------------------------------
    // authorize - decline (provider declines)
    // ---------------------------------------------------------------

    @Test
    void authorize_shouldReturnDeclinedPayment_andFailOrder_whenProviderDeclines() {
        UUID orderId = UUID.randomUUID();
        OrderEntity order = existingOrder(orderId, OrderStatus.PAYMENT_PENDING);
        when(orderService.get(orderId)).thenReturn(Mono.just(order));
        when(paymentProvider.authorize(any(PaymentEntity.class), any()))
                .thenReturn(Mono.just(PaymentAuthorizationResult.DECLINED));
        stubEchoPaymentSaves();
        stubEchoOrderSaves();

        StepVerifier.create(service.authorize(orderId, SimulatedOutcome.DECLINE))
                .assertNext(payment -> assertThat(payment.getStatus()).isEqualTo(PaymentStatus.DECLINED))
                .verifyComplete();

        ArgumentCaptor<OrderEntity> savedOrder = ArgumentCaptor.forClass(OrderEntity.class);
        verify(orderService).save(savedOrder.capture());
        assertThat(savedOrder.getValue().getStatus()).isEqualTo(OrderStatus.FAILED);
    }

    // ---------------------------------------------------------------
    // authorize - timeout (provider signals PaymentProviderTimeoutException)
    // ---------------------------------------------------------------

    @Test
    void authorize_shouldReturnTimedOutPayment_andFailOrder_whenProviderTimesOut() {
        UUID orderId = UUID.randomUUID();
        OrderEntity order = existingOrder(orderId, OrderStatus.PAYMENT_PENDING);
        when(orderService.get(orderId)).thenReturn(Mono.just(order));
        when(paymentProvider.authorize(any(PaymentEntity.class), any()))
                .thenReturn(Mono.error(new PaymentProviderTimeoutException(UUID.randomUUID())));
        stubEchoPaymentSaves();
        stubEchoOrderSaves();

        StepVerifier.create(service.authorize(orderId, SimulatedOutcome.TIMEOUT))
                .assertNext(payment -> assertThat(payment.getStatus()).isEqualTo(PaymentStatus.TIMED_OUT))
                .verifyComplete();

        ArgumentCaptor<OrderEntity> savedOrder = ArgumentCaptor.forClass(OrderEntity.class);
        verify(orderService).save(savedOrder.capture());
        assertThat(savedOrder.getValue().getStatus()).isEqualTo(OrderStatus.FAILED);
    }

    // ---------------------------------------------------------------
    // authorize - fail-fast when order is not awaiting payment
    // ---------------------------------------------------------------

    @Test
    void authorize_shouldSignalOrderNotPayableException_andNeverTouchPaymentOrProvider_whenOrderIsNotAwaitingPayment() {
        UUID orderId = UUID.randomUUID();
        OrderEntity order = existingOrder(orderId, OrderStatus.CREATED);
        when(orderService.get(orderId)).thenReturn(Mono.just(order));

        StepVerifier.create(service.authorize(orderId, SimulatedOutcome.SUCCESS))
                .expectErrorMatches(ex -> ex instanceof OrderNotPayableException
                        && ex.getMessage().contains(orderId.toString())
                        && ex.getMessage().contains(OrderStatus.CREATED.toString()))
                .verify();

        verify(paymentRepository, never()).save(any());
        verify(paymentProvider, never()).authorize(any(), any());
        verify(orderService, never()).save(any());
    }

    // ---------------------------------------------------------------
    // authorize - validation failure on the newly created payment
    // ---------------------------------------------------------------

    @Test
    void authorize_shouldSignalConstraintViolationException_andNeverCallProviderOrSaveOrder_whenOrderCurrencyIsInvalid() {
        UUID orderId = UUID.randomUUID();
        OrderEntity order = OrderEntity.builder()
                .id(orderId)
                .customerId("customer-1")
                .currency("ZZZ")
                .totalAmount(new BigDecimal("19.98"))
                .statusCode(OrderStatus.PAYMENT_PENDING.name())
                .version(3L)
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .items(List.of())
                .build();
        when(orderService.get(orderId)).thenReturn(Mono.just(order));

        StepVerifier.create(service.authorize(orderId, SimulatedOutcome.SUCCESS))
                .expectError(ConstraintViolationException.class)
                .verify();

        verify(paymentRepository, never()).save(any());
        verify(paymentProvider, never()).authorize(any(), any());
        verify(orderService, never()).save(any());
    }

    // ---------------------------------------------------------------
    // authorize - insert-then-update persistence shape (duplicate key risk)
    // ---------------------------------------------------------------

    @Test
    void authorize_shouldSaveThePendingPaymentAsAnInsert_thenSettleItAsAnUpdate_neverReInsertingTheSameRow() {
        UUID orderId = UUID.randomUUID();
        OrderEntity order = existingOrder(orderId, OrderStatus.PAYMENT_PENDING);
        when(orderService.get(orderId)).thenReturn(Mono.just(order));
        when(paymentProvider.authorize(any(PaymentEntity.class), any()))
                .thenReturn(Mono.just(PaymentAuthorizationResult.AUTHORIZED));
        stubEchoPaymentSaves();
        stubEchoOrderSaves();

        StepVerifier.create(service.authorize(orderId, SimulatedOutcome.SUCCESS))
                .expectNextCount(1)
                .verifyComplete();

        ArgumentCaptor<PaymentEntity> savedPayments = ArgumentCaptor.forClass(PaymentEntity.class);
        verify(paymentRepository, times(2)).save(savedPayments.capture());

        PaymentEntity firstSave = savedPayments.getAllValues().get(0);
        PaymentEntity secondSave = savedPayments.getAllValues().get(1);

        assertThat(firstSave.isNew())
                .as("the initial PENDING save must be a fresh insert")
                .isTrue();
        assertThat(secondSave.isNew())
                .as("the settling save must be an update of the same row, not a re-insert")
                .isFalse();
        assertThat(secondSave.getId())
                .as("both saves must target the same payment row")
                .isEqualTo(firstSave.getId());
    }

    // ---------------------------------------------------------------
    // get
    // ---------------------------------------------------------------

    @Test
    void get_shouldReturnPayment_whenRepositoryFindsIt() {
        UUID paymentId = UUID.randomUUID();
        PaymentEntity payment = PaymentEntity.createNew(UUID.randomUUID(), BigDecimal.TEN, "USD").toBuilder()
                .id(paymentId).build();
        when(paymentRepository.findById(paymentId)).thenReturn(Mono.just(payment));

        StepVerifier.create(service.get(paymentId))
                .expectNext(payment)
                .verifyComplete();
    }

    @Test
    void get_shouldSignalPaymentNotFoundException_whenRepositoryCompletesEmpty() {
        UUID paymentId = UUID.randomUUID();
        when(paymentRepository.findById(paymentId)).thenReturn(Mono.empty());

        StepVerifier.create(service.get(paymentId))
                .expectErrorMatches(ex -> ex instanceof PaymentNotFoundException
                        && ex.getMessage().contains(paymentId.toString()))
                .verify();
    }
}
