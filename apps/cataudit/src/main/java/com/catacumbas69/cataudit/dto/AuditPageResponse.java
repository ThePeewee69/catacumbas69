package com.catacumbas69.cataudit.dto;

import java.util.List;

/**
 * One page of audit entries, newest first.
 *
 * @param items the entries
 * @param page zero-based page
 * @param size entries per page
 * @param total entries matching the filters
 */
public record AuditPageResponse(List<AuditEventResponse> items, int page, int size, long total) {
}
