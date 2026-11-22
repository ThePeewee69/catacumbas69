package com.catacumbas69.catasonarr.metadata;

/**
 * TVmaze (or TMDB, where it is needed) did not answer. The API answers {@code 503}.
 */
public class MetadataUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what was asked
     * @param cause the failure
     */
    public MetadataUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
