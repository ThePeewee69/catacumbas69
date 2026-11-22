package com.catacumbas69.catasonarr.model;

import java.util.Map;

/**
 * One condition of a custom format, as Sonarr stores it: its implementation (e.g. {@code ReleaseTitleSpecification})
 * and its field values (e.g. {@code value}, {@code min}, {@code max}, {@code exceptLanguage}). Kept, not evaluated
 * until the search (phase 2, orch.md decision 112).
 *
 * @param implementation Sonarr's class name, e.g. {@code ReleaseTitleSpecification}
 * @param name the condition's name
 * @param negate whether it must not match
 * @param required whether it must match for the format to apply
 * @param values its fields by name
 */
public record Specification(String implementation, String name, boolean negate, boolean required,
                            Map<String, Object> values) {
}
