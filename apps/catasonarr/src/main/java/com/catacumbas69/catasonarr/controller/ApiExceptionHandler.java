package com.catacumbas69.catasonarr.controller;

import com.catacumbas69.catasonarr.dto.MessageResponse;
import com.catacumbas69.catasonarr.dto.ValidationFailure;
import com.catacumbas69.catasonarr.metadata.MetadataUnavailableException;
import com.catacumbas69.catasonarr.service.BadRequestException;
import com.catacumbas69.catasonarr.service.NotFoundException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Errors as Sonarr answers them (orch.md decision 112): {@code 400} with a list of validation failures,
 * {@code 404} with {@code {"message": …}}, {@code 503} when TVmaze or MongoDB do not answer.
 */
@RestControllerAdvice(basePackageClasses = ApiExceptionHandler.class)
public class ApiExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /**
     * A refused request.
     *
     * @param e the reason
     * @return {@code 400}
     */
    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<List<ValidationFailure>> badRequest(BadRequestException e) {
        return ResponseEntity.badRequest().body(List.of(new ValidationFailure(e.propertyName(), e.getMessage(),
                "error")));
    }

    /**
     * Something asked for does not exist.
     *
     * @param e what
     * @return {@code 404}
     */
    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<MessageResponse> notFound(NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new MessageResponse("NotFound: " + e.getMessage()));
    }

    /**
     * Two requests added the same thing at once (a unique index refused the second).
     *
     * @param e the refusal
     * @return {@code 409}
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<MessageResponse> duplicate(DuplicateKeyException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new MessageResponse("Already exists"));
    }

    /**
     * TVmaze or TMDB did not answer.
     *
     * @param e the failure
     * @return {@code 503}
     */
    @ExceptionHandler(MetadataUnavailableException.class)
    public ResponseEntity<MessageResponse> metadata(MetadataUnavailableException e) {
        LOG.warn("Metadata unavailable: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new MessageResponse("Metadata source unavailable: " + e.getMessage()));
    }

    /**
     * MongoDB did not answer.
     *
     * @param e the failure
     * @return {@code 503}
     */
    @ExceptionHandler(DataAccessResourceFailureException.class)
    public ResponseEntity<MessageResponse> mongo(DataAccessResourceFailureException e) {
        LOG.warn("MongoDB unavailable: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new MessageResponse("MongoDB unavailable"));
    }
}
