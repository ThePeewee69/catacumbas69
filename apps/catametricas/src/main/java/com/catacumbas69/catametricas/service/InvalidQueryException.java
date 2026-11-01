package com.catacumbas69.catametricas.service;

/**
 * A query parameter is missing, malformed or out of range. Mapped to {@code 400}.
 */
public class InvalidQueryException extends RuntimeException {

    /**
     * Creates the exception.
     *
     * @param message what is wrong, shown to the caller
     */
    public InvalidQueryException(String message) {
        super(message);
    }
}
