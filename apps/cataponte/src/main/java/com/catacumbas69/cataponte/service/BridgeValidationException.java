package com.catacumbas69.cataponte.service;

/**
 * A bridge definition breaks a rule that spans several fields. Mapped to {@code 400}.
 */
public class BridgeValidationException extends RuntimeException {

    private final String field;

    /**
     * Creates the exception.
     *
     * @param field request field the problem belongs to
     * @param message explanation shown to the user
     */
    public BridgeValidationException(String field, String message) {
        super(message);
        this.field = field;
    }

    /**
     * Returns the field the problem belongs to.
     *
     * @return field name
     */
    public String getField() {
        return field;
    }
}
