package com.catacumbas69.passario.service;

/**
 * No bridge exists with the requested id. Mapped to {@code 404}.
 */
public class BridgeNotFoundException extends RuntimeException {

    /**
     * Creates the exception.
     *
     * @param id requested bridge id
     */
    public BridgeNotFoundException(String id) {
        super("Bridge '" + id + "' does not exist");
    }
}
