package com.catacumbas69.catasonarr.service;

/**
 * What was asked for does not exist: the API answers {@code 404}, as Sonarr does.
 */
public class NotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what was not found, e.g. {@code Series 12}
     */
    public NotFoundException(String message) {
        super(message);
    }
}
