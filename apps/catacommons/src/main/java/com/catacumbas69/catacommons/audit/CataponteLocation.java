package com.catacumbas69.catacommons.audit;

/**
 * Where Cataponte is, read at every registration attempt. By default {@code catacommons.audit-bridge.cataponte-url};
 * Catapulsar provides its own (the URL editable in its settings).
 */
@FunctionalInterface
public interface CataponteLocation {

    /**
     * Returns Cataponte's base URL.
     *
     * @return e.g. {@code http://cataponte:8082}, without a trailing slash
     */
    String baseUrl();
}
