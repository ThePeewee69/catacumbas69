package com.catacumbas69.cataudit.service;

import com.catacumbas69.cataudit.model.AuditEvent;
import java.util.List;

/**
 * One page of audit entries, newest first.
 *
 * @param items the entries
 * @param page zero-based page
 * @param size entries per page
 * @param total entries matching the filters
 */
public record AuditPage(List<AuditEvent> items, int page, int size, long total) {
}
