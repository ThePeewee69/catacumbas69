package com.catacumbas69.passario.controller;

import com.catacumbas69.passario.service.BridgeConflictException;
import com.catacumbas69.passario.service.BridgeNotFoundException;
import com.catacumbas69.passario.service.BridgeValidationException;
import java.util.List;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps every error to RFC 9457 Problem Details (API-4). Clients read {@code title} and {@code detail};
 * validation errors also carry an {@code errors} list of {@link FieldViolation}.
 */
@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final String ERRORS = "errors";

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        List<FieldViolation> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldViolation(error.getField(), error.getDefaultMessage()))
                .toList();
        ProblemDetail problem = ex.getBody();
        problem.setTitle("Invalid request");
        problem.setDetail(violations.stream()
                .map(violation -> violation.field() + " " + violation.message())
                .collect(Collectors.joining("; ")));
        problem.setProperty(ERRORS, violations);
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    /**
     * Unknown bridge id.
     *
     * @param ex the exception
     * @return {@code 404}
     */
    @ExceptionHandler(BridgeNotFoundException.class)
    public ProblemDetail handleNotFound(BridgeNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Bridge not found", ex.getMessage());
    }

    /**
     * Duplicate name or stale version.
     *
     * @param ex the exception
     * @return {@code 409}
     */
    @ExceptionHandler(BridgeConflictException.class)
    public ProblemDetail handleConflict(BridgeConflictException ex) {
        return problem(HttpStatus.CONFLICT, "Conflict", ex.getMessage());
    }

    /**
     * Cross-field validation failure.
     *
     * @param ex the exception
     * @return {@code 400} with one {@link FieldViolation}
     */
    @ExceptionHandler(BridgeValidationException.class)
    public ProblemDetail handleValidation(BridgeValidationException ex) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Invalid request", ex.getMessage());
        problem.setProperty(ERRORS, List.of(new FieldViolation(ex.getField(), ex.getMessage())));
        return problem;
    }

    /**
     * MongoDB unreachable (RES-1): the API answers quickly instead of hanging.
     *
     * @param ex the exception
     * @return {@code 503}
     */
    @ExceptionHandler(DataAccessResourceFailureException.class)
    public ProblemDetail handleDatabaseUnavailable(DataAccessResourceFailureException ex) {
        log.warn("MongoDB unavailable: {}", ex.getMessage());
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Database unavailable",
                "The bridge store is temporarily unavailable; try again shortly");
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}
