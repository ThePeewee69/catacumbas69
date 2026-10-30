package com.catacumbas69.passario.controller;

/**
 * One invalid field, listed in the {@code errors} extension of a {@code 400} Problem Details response.
 *
 * @param field request field path, e.g. {@code destinations[0].topic}
 * @param message what is wrong
 */
public record FieldViolation(String field, String message) {
}
