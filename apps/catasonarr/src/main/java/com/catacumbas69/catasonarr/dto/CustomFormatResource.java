package com.catacumbas69.catasonarr.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Sonarr's {@code CustomFormatResource}.
 *
 * @param id the id
 * @param name the name
 * @param includeCustomFormatWhenRenaming whether its name goes into file names
 * @param specifications its conditions
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CustomFormatResource(int id, String name, boolean includeCustomFormatWhenRenaming,
                                   List<Specification> specifications) {

    /**
     * Sonarr's {@code CustomFormatSpecificationSchema}.
     *
     * @param id 0
     * @param name the condition's name
     * @param implementation e.g. {@code ReleaseTitleSpecification}
     * @param implementationName e.g. {@code Release Title}
     * @param infoLink Sonarr's wiki
     * @param negate must not match
     * @param required must match
     * @param fields its fields
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Specification(int id, String name, String implementation, String implementationName,
                                String infoLink, boolean negate, boolean required, List<Field> fields) {
    }

    /**
     * Sonarr's {@code Field}.
     *
     * @param order its order
     * @param name e.g. {@code value}, {@code min}, {@code max}
     * @param label what the user reads
     * @param unit e.g. {@code GB}
     * @param value its value
     * @param type {@code textbox}, {@code select}, {@code number}, {@code checkbox}
     * @param advanced {@code false}
     * @param privacy {@code normal}
     * @param isFloat whether a number may have decimals
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Field(int order, String name, String label, String unit, Object value, String type,
                        boolean advanced, String privacy, boolean isFloat) {
    }
}
