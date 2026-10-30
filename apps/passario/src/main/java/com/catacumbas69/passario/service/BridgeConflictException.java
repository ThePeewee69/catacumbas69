package com.catacumbas69.passario.service;

/**
 * The change conflicts with the stored state: duplicate name or stale version (RES-9). Mapped to
 * {@code 409}.
 */
public class BridgeConflictException extends RuntimeException {

    /**
     * Creates the exception.
     *
     * @param message explanation shown to the user
     */
    public BridgeConflictException(String message) {
        super(message);
    }
}
