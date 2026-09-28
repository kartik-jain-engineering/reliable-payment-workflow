package com.ledgerflow.order.model.entity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.ledgerflow.order.model.OrderStatus;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bean Validation unit tests for {@link OrderEntity} / {@link OrderItemEntity},
 * run against a plain {@link Validator} — the same mechanism
 * {@code OrderService} uses as a persistence-boundary safety net, on top of
 * the {@code CreateOrderRequest} validation already performed at the API
 * boundary.
 */
class OrderEntityValidationTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidatorFactory() {
        validatorFactory.close();
    }

    private static OrderItemEntity validItem() {
        return OrderItemEntity.builder()
                .id(UUID.randomUUID())
                .productId("sku-1")
                .quantity(2)
                .unitPrice(new BigDecimal("9.99"))
                .build();
    }

    private static OrderEntity.OrderEntityBuilder validOrderBuilder() {
        OffsetDateTime now = OffsetDateTime.now();
        return OrderEntity.builder()
                .id(UUID.randomUUID())
                .customerId("customer-1")
                .currency("USD")
                .totalAmount(new BigDecimal("19.98"))
                .statusCode(OrderStatus.CREATED.name())
                .version(0L)
                .createdAt(now)
                .updatedAt(now)
                .items(List.of(validItem()));
    }

    @Test
    void validOrder_shouldHaveNoViolations() {
        assertThat(validator.validate(validOrderBuilder().build())).isEmpty();
    }

    // ---------------------------------------------------------------
    // customerId: not blank
    // ---------------------------------------------------------------

    @Test
    void shouldRejectNullCustomerId() {
        OrderEntity order = validOrderBuilder().customerId(null).build();

        assertHasViolationOn(validator.validate(order), "customerId");
    }

    @Test
    void shouldRejectBlankCustomerId() {
        OrderEntity order = validOrderBuilder().customerId("   ").build();

        assertHasViolationOn(validator.validate(order), "customerId");
    }

    // ---------------------------------------------------------------
    // currency: valid ISO 4217 code
    // ---------------------------------------------------------------

    @Test
    void shouldRejectCurrencyCode_thatIsWellFormedButNotRealIso4217() {
        // "ZZZ" would pass a naive [A-Z]{3} pattern check but is not a real currency.
        OrderEntity order = validOrderBuilder().currency("ZZZ").build();

        assertHasViolationOn(validator.validate(order), "currency");
    }

    @Test
    void shouldRejectBlankCurrency() {
        OrderEntity order = validOrderBuilder().currency("  ").build();

        assertHasViolationOn(validator.validate(order), "currency");
    }

    @Test
    void shouldRejectNullCurrency() {
        OrderEntity order = validOrderBuilder().currency(null).build();

        assertHasViolationOn(validator.validate(order), "currency");
    }

    @Test
    void shouldAcceptRealIsoCurrencyCode() {
        OrderEntity order = validOrderBuilder().currency("EUR").build();

        assertThat(validator.validate(order)).isEmpty();
    }

    // ---------------------------------------------------------------
    // totalAmount: strictly positive
    // ---------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"0", "-5.00"})
    void shouldRejectTotalAmount_thatIsNotStrictlyPositive(String amount) {
        OrderEntity order = validOrderBuilder().totalAmount(new BigDecimal(amount)).build();

        assertHasViolationOn(validator.validate(order), "totalAmount");
    }

    @Test
    void shouldRejectNullTotalAmount() {
        OrderEntity order = validOrderBuilder().totalAmount(null).build();

        assertHasViolationOn(validator.validate(order), "totalAmount");
    }

    // ---------------------------------------------------------------
    // items: not empty, no null entries, each item individually valid
    // ---------------------------------------------------------------

    @Test
    void shouldRejectEmptyItemsList() {
        OrderEntity order = validOrderBuilder().items(List.of()).build();

        assertHasViolationOn(validator.validate(order), "items");
    }

    @Test
    void shouldRejectNullEntryInItemsList() {
        List<OrderItemEntity> itemsWithNull = new ArrayList<>();
        itemsWithNull.add(null);
        OrderEntity order = validOrderBuilder().items(itemsWithNull).build();

        assertThat(validator.validate(order))
                .as("expected a violation under 'items' for the null entry")
                .anySatisfy(violation -> assertThat(violation.getPropertyPath().toString()).startsWith("items"));
    }

    @Test
    void shouldRejectOrder_whenANestedItemIsInvalid() {
        OrderItemEntity invalidItem = validItem().toBuilder().productId(" ").build();
        OrderEntity order = validOrderBuilder().items(List.of(invalidItem)).build();

        assertThat(validator.validate(order))
                .as("expected the item's own violation to cascade up through 'items'")
                .anySatisfy(violation -> assertThat(violation.getPropertyPath().toString()).contains("productId"));
    }

    // ---------------------------------------------------------------
    // OrderItemEntity
    // ---------------------------------------------------------------

    @Test
    void validItem_shouldHaveNoViolations() {
        assertThat(validator.validate(validItem())).isEmpty();
    }

    @Test
    void shouldRejectItemWithBlankProductId() {
        OrderItemEntity item = validItem().toBuilder().productId(" ").build();

        assertHasViolationOn(validator.validate(item), "productId");
    }

    @Test
    void shouldRejectItemQuantity_thatIsZeroOrNegative() {
        OrderItemEntity item = validItem().toBuilder().quantity(0).build();

        assertHasViolationOn(validator.validate(item), "quantity");
    }

    @Test
    void shouldRejectItemUnitPrice_thatIsNegative() {
        OrderItemEntity item = validItem().toBuilder().unitPrice(new BigDecimal("-0.01")).build();

        assertHasViolationOn(validator.validate(item), "unitPrice");
    }

    @Test
    void shouldAcceptItemUnitPrice_thatIsZero() {
        OrderItemEntity item = validItem().toBuilder().unitPrice(BigDecimal.ZERO).build();

        assertThat(validator.validate(item)).isEmpty();
    }

    private static <T> void assertHasViolationOn(Set<ConstraintViolation<T>> violations, String propertyPath) {
        assertThat(violations)
                .as("expected a violation on property '%s'", propertyPath)
                .anySatisfy(violation ->
                        assertThat(violation.getPropertyPath().toString()).isEqualTo(propertyPath));
    }
}
