package com.catacumbas69.catapulsar.dto;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A tenant, namespace, topic or subscription name: letters, digits, {@code . _ = : -}, at most 200
 * characters (API-5, SEC-4).
 */
@NotBlank
@Size(max = 200)
@Pattern(regexp = ValidationPatterns.NAME)
@ReportAsSingleViolation
@Constraint(validatedBy = {})
@Documented
@Target({ElementType.PARAMETER, ElementType.FIELD, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface PulsarName {

    /**
     * Violation message.
     *
     * @return the message
     */
    String message() default ValidationPatterns.NAME_MESSAGE;

    /**
     * Validation groups.
     *
     * @return the groups
     */
    Class<?>[] groups() default {};

    /**
     * Payload for clients of the constraint.
     *
     * @return the payload
     */
    Class<? extends Payload>[] payload() default {};
}
