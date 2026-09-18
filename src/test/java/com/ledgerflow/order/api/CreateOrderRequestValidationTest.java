package com.ledgerflow.order.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bean Validation unit tests for {@link CreateOrderRequest} /
 * {@link CreateOrderItemRequest}, run against a plain {@link Validator} —
 * no Spring context needed since the constraints are pure Jakarta Bean
 * Validation annotations plus the module's own {@code @ValidCurrencyCode}.
 */
class CreateOrderRequestValidationTest {

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

    private static CreateOrderItemRequest validItem() {
        return new CreateOrderItemRequest("sku-1", 2, new BigDecimal("9.99"));
    }

    private static CreateOrderRequest validRequest() {
        return new CreateOrderRequest("customer-1", "USD", new BigDecimal("19.98"), List.of(validItem()));
    }

    @Test
    void validRequest_shouldHaveNoViolations() {
        Set<ConstraintViolation<CreateOrderRequest>> violations = validator.validate(validRequest());

        assertThat(violations).isEmpty();
    }

    // ---------------------------------------------------------------
    // customerId: not blank
    // ---------------------------------------------------------------

    @Test
    void shouldRejectNullCustomerId() {
        CreateOrderRequest request = new CreateOrderRequest(null, "USD", new BigDecimal("10"), List.of(validItem()));

        assertHasViolationOn(validator.validate(request), "customerId");
    }

    @Test
    void shouldRejectBlankCustomerId() {
        CreateOrderRequest request = new CreateOrderRequest("   ", "USD", new BigDecimal("10"), List.of(validItem()));

        assertHasViolationOn(validator.validate(request), "customerId");
    }

    // ---------------------------------------------------------------
    // currency: valid ISO 4217 code
    // ---------------------------------------------------------------

    @Test
    void shouldRejectCurrencyCode_thatIsWellFormedButNotRealIso4217() {
        // "ZZZ" would pass a naive [A-Z]{3} pattern check but is not a real currency.
        CreateOrderRequest request =
                new CreateOrderRequest("customer-1", "ZZZ", new BigDecimal("10"), List.of(validItem()));

        assertHasViolationOn(validator.validate(request), "currency");
    }

    @Test
    void shouldRejectBlankCurrency() {
        CreateOrderRequest request =
                new CreateOrderRequest("customer-1", "  ", new BigDecimal("10"), List.of(validItem()));

        assertHasViolationOn(validator.validate(request), "currency");
    }

    @Test
    void shouldAcceptRealIsoCurrencyCode() {
        CreateOrderRequest request =
                new CreateOrderRequest("customer-1", "EUR", new BigDecimal("10"), List.of(validItem()));

        assertThat(validator.validate(request)).isEmpty();
    }

    // ---------------------------------------------------------------
    // totalAmount: strictly positive
    // ---------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"0", "-5.00"})
    void shouldRejectTotalAmount_thatIsNotStrictlyPositive(String amount) {
        CreateOrderRequest request =
                new CreateOrderRequest("customer-1", "USD", new BigDecimal(amount), List.of(validItem()));

        assertHasViolationOn(validator.validate(request), "totalAmount");
    }

    // ---------------------------------------------------------------
    // items: not empty
    // ---------------------------------------------------------------

    @Test
    void shouldRejectEmptyItemsList() {
        CreateOrderRequest request = new CreateOrderRequest("customer-1", "USD", new BigDecimal("10"), List.of());

        assertHasViolationOn(validator.validate(request), "items");
    }

    // ---------------------------------------------------------------
    // item quantity: strictly positive
    // ---------------------------------------------------------------

    @Test
    void shouldRejectItemQuantity_thatIsZeroOrNegative() {
        CreateOrderItemRequest item = new CreateOrderItemRequest("sku-1", 0, BigDecimal.ONE);

        Set<ConstraintViolation<CreateOrderItemRequest>> violations = validator.validate(item);

        assertHasViolationOn(violations, "quantity");
    }

    // ---------------------------------------------------------------
    // item unitPrice: never negative, zero allowed
    // ---------------------------------------------------------------

    @Test
    void shouldRejectItemUnitPrice_thatIsNegative() {
        CreateOrderItemRequest item = new CreateOrderItemRequest("sku-1", 1, new BigDecimal("-0.01"));

        assertHasViolationOn(validator.validate(item), "unitPrice");
    }

    @Test
    void shouldAcceptItemUnitPrice_thatIsZero() {
        CreateOrderItemRequest item = new CreateOrderItemRequest("sku-1", 1, BigDecimal.ZERO);

        assertThat(validator.validate(item)).isEmpty();
    }

    private static <T> void assertHasViolationOn(Set<ConstraintViolation<T>> violations, String propertyPath) {
        assertThat(violations)
                .as("expected a violation on property '%s'", propertyPath)
                .anySatisfy(violation ->
                        assertThat(violation.getPropertyPath().toString()).isEqualTo(propertyPath));
    }
}
