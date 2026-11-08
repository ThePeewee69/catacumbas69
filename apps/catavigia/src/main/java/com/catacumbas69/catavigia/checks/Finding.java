package com.catacumbas69.catavigia.checks;

import java.util.Map;

/**
 * A problem a check sees now.
 *
 * @param type alert type
 * @param subject what it is about
 * @param details what was found (backlog, limit, error…), shown with the alert
 */
public record Finding(String type, String subject, Map<String, String> details) {
}
