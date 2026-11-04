package com.catacumbas69.catametricas.controller;

import com.catacumbas69.catametricas.service.InvalidQueryException;
import org.apache.pulsar.client.admin.PulsarAdminException;
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
     * A query parameter does not fit.
     *
     * @param ex the failure
     * @return {@code 400}
     */
    @ExceptionHandler(InvalidQueryException.class)
    public ProblemDetail handleInvalidQuery(InvalidQueryException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Invalid query");
        return problem;
    }

    /**
     * Pulsar cannot be read (e.g. the namespaces of a forced collection cannot be listed).
     *
     * @param ex the failure
     * @return {@code 502}
     */
    @ExceptionHandler(PulsarAdminException.class)
    public ProblemDetail handlePulsarUnavailable(PulsarAdminException ex) {
        LOG.warn("Pulsar admin call failed: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, ex.getMessage());
        problem.setTitle("Pulsar unavailable");
        return problem;
    }

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
