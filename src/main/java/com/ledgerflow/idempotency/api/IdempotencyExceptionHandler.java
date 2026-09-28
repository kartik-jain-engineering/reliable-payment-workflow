package com.ledgerflow.idempotency.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.ledgerflow.idempotency.service.IdempotencyKeyInProgressException;
import com.ledgerflow.idempotency.service.IdempotencyKeyReusedException;

// Unscoped on purpose: idempotency has no controllers of its own; its exceptions surface from other modules' endpoints.
@RestControllerAdvice
public class IdempotencyExceptionHandler {

    @ExceptionHandler(IdempotencyKeyReusedException.class)
    ProblemDetail handleKeyReused(IdempotencyKeyReusedException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Idempotency key reused", ex.getMessage());
    }

    @ExceptionHandler(IdempotencyKeyInProgressException.class)
    ProblemDetail handleKeyInProgress(IdempotencyKeyInProgressException ex) {
        return problem(HttpStatus.CONFLICT, "Request in progress", ex.getMessage());
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}
