package com.catacumbas69.pulsario.service;

/**
 * A setting value was rejected. Mapped to {@code 400} with the field name.
 */
public class InvalidSettingException extends RuntimeException {

    private final String field;

    /**
     * Creates the exception.
     *
     * @param field request field the problem belongs to
     * @param message explanation shown to the user
     */
    public InvalidSettingException(String field, String message) {
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
