package com.catacumbas69.pulsario.service;

/**
 * Passário could not be reached or did not answer in time. Mapped to {@code 503}.
 */
public class PassarioUnavailableException extends RuntimeException {

    /**
     * Creates the exception.
     *
     * @param cause the underlying I/O failure
     */
    public PassarioUnavailableException(Throwable cause) {
        super("Passário is not reachable: " + cause.getMessage(), cause);
    }
}
