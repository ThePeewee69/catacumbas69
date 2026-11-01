package com.catacumbas69.catapulsar.dto;

/**
 * Regular expressions for path variables (API-5, SEC-4). They keep user input out of Pulsar admin
 * paths unless it is a plain name.
 */
public final class ValidationPatterns {

    /**
     * Tenant, namespace, topic and subscription names. A leading {@code _} is allowed for Pulsar's own names
     * ({@code __compaction}, {@code __change_events}).
     */
    public static final String NAME = "^[A-Za-z0-9_][A-Za-z0-9._=:-]*$";

    /** Message shown when {@link #NAME} does not match. */
    public static final String NAME_MESSAGE = "must start with a letter, a digit or '_' and may contain only letters, "
            + "digits, '.', '_', '=', ':' and '-'";

    private ValidationPatterns() {
    }
}
