package com.catacumbas69.catasonarr.service;

/**
 * A request Catasonarr refuses: the API answers {@code 400} with Sonarr's list of validation failures.
 */
public class BadRequestException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String propertyName;

    /**
     * Creates the exception.
     *
     * @param propertyName the field at fault, e.g. {@code path}, or {@code null}
     * @param message why, as the user reads it
     */
    public BadRequestException(String propertyName, String message) {
        super(message);
        this.propertyName = propertyName;
    }

    /**
     * Returns the field at fault.
     *
     * @return the field, or {@code null}
     */
    public String propertyName() {
        return propertyName;
    }
}
