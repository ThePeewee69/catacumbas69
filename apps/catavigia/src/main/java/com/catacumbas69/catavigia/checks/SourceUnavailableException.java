package com.catacumbas69.catavigia.checks;

/**
 * A check could not read its source (an app, Catametricas, Pulsar's admin API).
 */
public class SourceUnavailableException extends Exception {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what could not be read, and why
     * @param cause the failure
     */
    public SourceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
