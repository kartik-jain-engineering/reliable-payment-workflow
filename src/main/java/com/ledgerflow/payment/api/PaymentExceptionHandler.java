package com.ledgerflow.payment.api;

import java.util.List;

import jakarta.validation.ConstraintViolationException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.MissingRequestValueException;

import com.ledgerflow.order.service.OrderAccessDeniedException;
import com.ledgerflow.order.service.OrderNotFoundException;
import com.ledgerflow.payment.model.InvalidStateTransitionException;
import com.ledgerflow.payment.service.OrderNotPayableException;
import com.ledgerflow.payment.service.PaymentNotFoundException;

@RestControllerAdvice(basePackages = "com.ledgerflow.payment")
public class PaymentExceptionHandler {

    @ExceptionHandler(PaymentNotFoundException.class)
    ProblemDetail handlePaymentNotFound(PaymentNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Payment not found", ex.getMessage());
    }

    // OrderExceptionHandler is scoped to basePackages = "com.ledgerflow.order", so it never sees
    // order exceptions raised from PaymentController.
    @ExceptionHandler(OrderNotFoundException.class)
    ProblemDetail handleOrderNotFound(OrderNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Order not found", ex.getMessage());
    }

    @ExceptionHandler(OrderAccessDeniedException.class)
    ProblemDetail handleOrderAccessDenied(OrderAccessDeniedException ex) {
        return problem(HttpStatus.FORBIDDEN, "Access denied", ex.getMessage());
    }

    @ExceptionHandler(MissingRequestValueException.class)
    ProblemDetail handleMissingRequestValue(MissingRequestValueException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Missing request value", ex.getReason());
    }

    @ExceptionHandler(InvalidStateTransitionException.class)
    ProblemDetail handleInvalidStateTransition(InvalidStateTransitionException ex) {
        return problem(HttpStatus.CONFLICT, "Invalid payment state transition", ex.getMessage());
    }

    @ExceptionHandler(OrderNotPayableException.class)
    ProblemDetail handleOrderNotPayable(OrderNotPayableException ex) {
        return problem(HttpStatus.CONFLICT, "Order not payable", ex.getMessage());
    }

    @ExceptionHandler(WebExchangeBindException.class)
    ProblemDetail handleValidationFailure(WebExchangeBindException ex) {
        List<FieldViolation> violations = ex.getFieldErrors().stream()
                .map(error -> new FieldViolation(error.getField(), error.getDefaultMessage()))
                .toList();

        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Validation failed", "Request validation failed");
        problem.setProperty("errors", violations);
        return problem;
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ProblemDetail handleConstraintViolation(ConstraintViolationException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid payment", ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleInvalidArgument(IllegalArgumentException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid payment", ex.getMessage());
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }

    record FieldViolation(String field, String message) {
    }
}
