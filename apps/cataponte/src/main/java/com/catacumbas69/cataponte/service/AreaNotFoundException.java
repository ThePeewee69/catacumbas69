package com.catacumbas69.cataponte.service;

/**
 * No area has the requested id. Mapped to {@code 404}.
 */
public class AreaNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param id the unknown id
     */
    public AreaNotFoundException(String id) {
        super("No area with id '" + id + "'");
    }
}
