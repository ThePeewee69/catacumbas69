package com.catacumbas69.cataponte.dto;

import com.catacumbas69.cataponte.model.AreaDocument;
import java.time.Instant;
import java.util.List;

/**
 * An area as returned by the API.
 *
 * @param id area id
 * @param name unique name
 * @param description free text
 * @param patterns glob patterns over topic names; empty = every topic
 * @param bridgeCount number of bridges in the area
 * @param version version to send back on update
 * @param createdAt creation time
 * @param updatedAt last change time
 */
public record AreaResponse(
        String id,
        String name,
        String description,
        List<String> patterns,
        long bridgeCount,
        Long version,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * Converts a stored area.
     *
     * @param area the area
     * @param bridgeCount its number of bridges
     * @return the response
     */
    public static AreaResponse of(AreaDocument area, long bridgeCount) {
        return new AreaResponse(area.getId(), area.getName(), area.getDescription(),
                area.getPatterns() == null ? List.of() : List.copyOf(area.getPatterns()), bridgeCount,
                area.getVersion(), area.getCreatedAt(), area.getUpdatedAt());
    }

    /**
     * Builds an area from a request.
     *
     * @param request validated request
     * @return a new, unsaved area
     */
    public static AreaDocument toDocument(AreaRequest request) {
        AreaDocument area = new AreaDocument();
        area.setName(request.name().trim());
        area.setDescription(request.description());
        area.setPatterns(request.patterns() == null ? List.of() : request.patterns());
        return area;
    }
}
