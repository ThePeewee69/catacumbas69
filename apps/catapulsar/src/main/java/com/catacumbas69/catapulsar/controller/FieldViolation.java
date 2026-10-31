package com.catacumbas69.catapulsar.controller;

/**
 * One invalid input, listed in the {@code errors} extension of a {@code 400} Problem Details response.
 *
 * @param field field or parameter name
 * @param message what is wrong
 */
public record FieldViolation(String field, String message) {
}
