package com.catacumbas69.catapulsar.service;

/**
 * Cataponte could not be reached or did not answer in time. Mapped to {@code 503}.
 */
public class CataponteUnavailableException extends RuntimeException {

    /**
     * Creates the exception.
     *
     * @param cause the underlying I/O failure
     */
    public CataponteUnavailableException(Throwable cause) {
        super("Cataponte is not reachable: " + cause.getMessage(), cause);
    }
}
