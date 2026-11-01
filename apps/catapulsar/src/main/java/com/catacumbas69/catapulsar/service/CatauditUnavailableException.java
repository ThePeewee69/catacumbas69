package com.catacumbas69.catapulsar.service;

/**
 * Cataudit could not be reached or did not answer in time. Mapped to {@code 503}.
 */
public class CatauditUnavailableException extends RuntimeException {

    /**
     * Creates the exception.
     *
     * @param cause the underlying I/O failure
     */
    public CatauditUnavailableException(Throwable cause) {
        super("Cataudit is not reachable: " + cause.getMessage(), cause);
    }
}
