package com.catacumbas69.cataudit.service;

import java.time.Instant;

/**
 * Filters of an audit search; {@code null} means "any".
 *
 * @param app app name, exact
 * @param entity entity, exact (e.g. {@code topic})
 * @param action action, exact (e.g. {@code created})
 * @param subject start of the subject (e.g. {@code persistent://tenant/ns/})
 * @param actor actor, exact
 * @param from first instant included
 * @param to end of the range, excluded
 * @param page zero-based page
 * @param size entries per page
 */
public record AuditQuery(String app, String entity, String action, String subject, String actor, Instant from,
                         Instant to, int page, int size) {
}
