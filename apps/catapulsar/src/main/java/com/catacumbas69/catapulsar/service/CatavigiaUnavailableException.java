package com.catacumbas69.catapulsar.service;

/**
 * Catavigia could not be reached or did not answer in time. Mapped to {@code 503}.
 */
public class CatavigiaUnavailableException extends RuntimeException {

    /**
     * Creates the exception.
     *
     * @param cause the underlying I/O failure
     */
    public CatavigiaUnavailableException(Throwable cause) {
        super("Catavigia is not reachable: " + cause.getMessage(), cause);
    }
}
