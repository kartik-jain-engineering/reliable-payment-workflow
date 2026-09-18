package com.ledgerflow.order.api.constraint;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.RECORD_COMPONENT;

/**
 * Asserts that the annotated value is a currency code the JDK recognises as
 * ISO 4217 — a {@code [A-Z]{3}} pattern is not enough, since it happily
 * accepts codes like {@code ZZZ} that no currency uses.
 *
 * <p>{@code null} is considered valid; combine with {@code @NotNull} or
 * {@code @NotBlank} to report nullness once.
 */
@Documented
@Constraint(validatedBy = CurrencyCodeValidator.class)
@Target({FIELD, RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidCurrencyCode {

    String message() default "must be a valid ISO 4217 currency code";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
