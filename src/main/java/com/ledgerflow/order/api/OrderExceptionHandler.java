package com.ledgerflow.order.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;

import com.ledgerflow.order.domain.InvalidStateTransitionException;
import com.ledgerflow.order.domain.OrderNotFoundException;

/**
 * Translates order failures into HTTP responses.
 *
 * <p>Scoped to this module's packages so it cannot change the error
 * behaviour of other modules. Every response is an RFC 7807
 * {@link ProblemDetail}, chosen over a bespoke DTO because Spring already
 * ships it and it keeps the body shape identical across handlers.
 */
@RestControllerAdvice(basePackages = "com.ledgerflow.order")
public class OrderExceptionHandler {

    @ExceptionHandler(OrderNotFoundException.class)
    ProblemDetail handleOrderNotFound(OrderNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Order not found", ex.getMessage());
    }

    @ExceptionHandler(InvalidStateTransitionException.class)
    ProblemDetail handleInvalidStateTransition(InvalidStateTransitionException ex) {
        return problem(HttpStatus.CONFLICT, "Invalid order state transition", ex.getMessage());
    }

    /**
     * WebFlux raises {@link WebExchangeBindException} — not MVC's
     * {@code MethodArgumentNotValidException} — for a rejected
     * {@code @Valid @RequestBody}. It already carries a 400, but handling it
     * here is what keeps the {@link ProblemDetail} body shape identical to
     * every other error this module returns.
     */
    @ExceptionHandler(WebExchangeBindException.class)
    ProblemDetail handleValidationFailure(WebExchangeBindException ex) {
        List<FieldViolation> violations = ex.getFieldErrors().stream()
                .map(error -> new FieldViolation(error.getField(), error.getDefaultMessage()))
                .toList();

        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Validation failed", "Request validation failed");
        problem.setProperty("errors", violations);
        return problem;
    }

    /**
     * The aggregate re-checks the same rules the request model validates, so
     * an {@link IllegalArgumentException} escaping it still means "bad
     * request" — not a server fault.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleInvalidArgument(IllegalArgumentException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid order", ex.getMessage());
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }

    record FieldViolation(String field, String message) {
    }
}
