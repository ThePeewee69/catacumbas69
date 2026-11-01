package com.catacumbas69.catapulsar.service;

/**
 * Catametricas could not be reached, did not answer in time or answered with a server error. Mapped to
 * {@code 503}.
 */
public class CatametricasUnavailableException extends RuntimeException {

    /**
     * Creates the exception.
     *
     * @param cause the underlying failure
     */
    public CatametricasUnavailableException(Throwable cause) {
        super("Catametricas is not available: " + cause.getMessage(), cause);
    }
}
