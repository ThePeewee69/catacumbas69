package com.catacumbas69.catasonarr.parser;

/** A daily release's date that cannot be (Sonarr's {@code InvalidDateException}): the parser stops trying. */
public class InvalidDateException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * An invalid date.
     *
     * @param message what is wrong
     */
    public InvalidDateException(String message) {
        super(message);
    }
}
