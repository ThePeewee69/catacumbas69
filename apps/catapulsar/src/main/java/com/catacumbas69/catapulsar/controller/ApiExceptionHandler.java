package com.catacumbas69.catapulsar.controller;

import com.catacumbas69.catapulsar.service.InvalidSettingException;
import com.catacumbas69.catapulsar.service.CataponteUnavailableException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.ElementKind;
import jakarta.validation.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.apache.pulsar.client.api.PulsarClientException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.NonNull;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps every error to RFC 9457 Problem Details (API-4). The UI reads {@code detail}, falling back to
 * {@code title}; validation errors also carry an {@code errors} list of {@link FieldViolation}.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final String ERRORS = "errors";

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(@NonNull MethodArgumentNotValidException ex,
                                                                  @NonNull HttpHeaders headers,
                                                                  @NonNull HttpStatusCode status,
                                                                  @NonNull WebRequest request) {
        List<FieldViolation> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldViolation(error.getField(), error.getDefaultMessage()))
                .toList();
        return invalid(ex.getBody(), violations, ex, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            @NonNull HandlerMethodValidationException ex, @NonNull HttpHeaders headers,
            @NonNull HttpStatusCode status, @NonNull WebRequest request) {
        List<FieldViolation> violations = ex.getParameterValidationResults().stream()
                .flatMap(ApiExceptionHandler::violationsOf)
                .toList();
        return invalid(ex.getBody(), violations, ex, headers, status, request);
    }

    /**
     * Names each violation after the field that failed: for a request body ({@code @Valid}) that is the
     * property path inside the body (e.g. {@code payload}); for a path or query parameter it is the
     * parameter name (e.g. {@code tenant}).
     */
    private static Stream<FieldViolation> violationsOf(ParameterValidationResult result) {
        String parameter = result.getMethodParameter().getParameterName();
        return result.getResolvableErrors().stream()
                .map(error -> new FieldViolation(fieldOf(result, error, parameter), messageOf(error)));
    }

    private static String fieldOf(ParameterValidationResult result, MessageSourceResolvable error,
                                  String parameter) {
        if (error instanceof FieldError fieldError) {
            return fieldError.getField();
        }
        try {
            ConstraintViolation<?> violation = result.unwrap(error, ConstraintViolation.class);
            String path = propertyPathAfterParameter(violation.getPropertyPath());
            return path.isEmpty() ? parameter : path;
        } catch (IllegalArgumentException e) {
            return parameter;
        }
    }

    /** {@code produce.body.payload} → {@code payload}; {@code namespaces.tenant} → empty. */
    private static String propertyPathAfterParameter(Path path) {
        List<String> names = new ArrayList<>();
        for (Path.Node node : path) {
            if (node.getKind() == ElementKind.PROPERTY || node.getKind() == ElementKind.CONTAINER_ELEMENT) {
                names.add(node.getName());
            }
        }
        return String.join(".", names);
    }

    /**
     * Constraint violations raised outside Spring MVC's built-in method validation.
     *
     * @param ex the exception
     * @return {@code 400} with the violations
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(ConstraintViolationException ex) {
        List<FieldViolation> violations = ex.getConstraintViolations().stream()
                .map(violation -> new FieldViolation(lastNode(violation.getPropertyPath().toString()),
                        violation.getMessage()))
                .toList();
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Invalid request", violations.stream()
                .map(violation -> violation.field() + " " + violation.message())
                .collect(Collectors.joining("; ")));
        problem.setProperty(ERRORS, violations);
        return problem;
    }

    /**
     * Pulsar admin API errors keep their 4xx status (e.g. topic not found → 404, already exists → 409);
     * anything else means Pulsar is failing or unreachable (502).
     *
     * @param ex the exception
     * @return the problem
     */
    @ExceptionHandler(PulsarAdminException.class)
    public ResponseEntity<ProblemDetail> handlePulsarAdmin(PulsarAdminException ex) {
        HttpStatus status = statusFor(ex);
        if (status.is5xxServerError()) {
            LOG.warn("Pulsar admin call failed: {}", ex.getMessage());
        }
        String title = status.is5xxServerError() ? "Pulsar unavailable" : status.getReasonPhrase();
        return ResponseEntity.status(status).body(problem(status, title, pulsarMessage(ex)));
    }

    /**
     * Producing failed (broker unreachable, timeout, topic terminated, …).
     *
     * @param ex the exception
     * @return {@code 502}
     */
    @ExceptionHandler(PulsarClientException.class)
    public ProblemDetail handlePulsarClient(PulsarClientException ex) {
        LOG.warn("Pulsar client call failed: {}", ex.getMessage());
        return problem(HttpStatus.BAD_GATEWAY, "Pulsar unavailable", ex.getMessage());
    }

    /**
     * Cataponte cannot be reached; only the Bridges panel is affected (RES-12).
     *
     * @param ex the exception
     * @return {@code 503}
     */
    @ExceptionHandler(CataponteUnavailableException.class)
    public ProblemDetail handleCataponteUnavailable(CataponteUnavailableException ex) {
        LOG.warn(ex.getMessage());
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Cataponte unavailable", ex.getMessage());
    }

    /**
     * A setting value was rejected.
     *
     * @param ex the exception
     * @return {@code 400} with the offending field
     */
    @ExceptionHandler(InvalidSettingException.class)
    public ProblemDetail handleInvalidSetting(InvalidSettingException ex) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Invalid request",
                ex.getField() + " " + ex.getMessage());
        problem.setProperty(ERRORS, List.of(new FieldViolation(ex.getField(), ex.getMessage())));
        return problem;
    }

    /**
     * MongoDB cannot be reached while saving a setting (RES-1): answer quickly instead of failing with 500.
     *
     * @param ex the exception
     * @return {@code 503}
     */
    @ExceptionHandler(DataAccessResourceFailureException.class)
    public ProblemDetail handleDatabaseUnavailable(DataAccessResourceFailureException ex) {
        LOG.warn("MongoDB unavailable: {}", ex.getMessage());
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Database unavailable", "MongoDB cannot be reached");
    }

    private ResponseEntity<Object> invalid(ProblemDetail problem, List<FieldViolation> violations, Exception ex,
                                           HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        problem.setTitle("Invalid request");
        problem.setDetail(violations.stream()
                .map(violation -> violation.field() + " " + violation.message())
                .collect(Collectors.joining("; ")));
        problem.setProperty(ERRORS, violations);
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    private static HttpStatus statusFor(PulsarAdminException ex) {
        if (ex instanceof PulsarAdminException.NotFoundException) {
            return HttpStatus.NOT_FOUND;
        }
        if (ex instanceof PulsarAdminException.ConflictException) {
            return HttpStatus.CONFLICT;
        }
        if (ex instanceof PulsarAdminException.NotAuthorizedException) {
            return HttpStatus.FORBIDDEN;
        }
        if (ex instanceof PulsarAdminException.PreconditionFailedException) {
            return HttpStatus.PRECONDITION_FAILED;
        }
        HttpStatus resolved = HttpStatus.resolve(ex.getStatusCode());
        return resolved != null && resolved.is4xxClientError() ? resolved : HttpStatus.BAD_GATEWAY;
    }

    private static String pulsarMessage(PulsarAdminException ex) {
        return ex.getHttpError() != null && !ex.getHttpError().isBlank() ? ex.getHttpError() : ex.getMessage();
    }

    private static String lastNode(String path) {
        return path.substring(path.lastIndexOf('.') + 1);
    }

    private static String messageOf(MessageSourceResolvable error) {
        return error.getDefaultMessage() != null ? error.getDefaultMessage() : "is invalid";
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}
