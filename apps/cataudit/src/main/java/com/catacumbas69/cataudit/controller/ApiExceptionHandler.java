package com.catacumbas69.cataudit.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Problem Details for every error (API-4); Spring's own errors (bad parameters, 404) keep their status.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /**
     * MongoDB cannot be reached.
     *
     * @param ex the failure
     * @return {@code 503}
     */
    @ExceptionHandler(DataAccessResourceFailureException.class)
    public ProblemDetail handleDatabaseUnavailable(DataAccessResourceFailureException ex) {
        LOG.warn("MongoDB unavailable: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "MongoDB cannot be reached");
        problem.setTitle("Database unavailable");
        return problem;
    }
}
