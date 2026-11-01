package com.catacumbas69.cataudit.dto;

import java.util.List;

/**
 * Values in use, for the filter lists of the UI.
 *
 * @param apps app names
 * @param entities entities
 * @param actions actions
 */
public record AuditFacetsResponse(List<String> apps, List<String> entities, List<String> actions) {
}
