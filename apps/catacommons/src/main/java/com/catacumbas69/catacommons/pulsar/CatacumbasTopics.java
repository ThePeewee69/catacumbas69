package com.catacumbas69.catacommons.pulsar;

/**
 * Names of the Pulsar tenant, namespaces and topics the apps use to talk to each other (ADR 0008).
 *
 * <p>Every app owns the namespace {@code catacumbas69/<app>}; its events go to
 * {@code persistent://catacumbas69/<app>/events}.
 */
public final class CatacumbasTopics {

    /** Tenant of every namespace below. */
    public static final String TENANT = "catacumbas69";

    /** Local name of each app's event topic. */
    public static final String EVENTS = "events";

    /** Local name of Catapulsar's compacted settings topic (ADR 0011). */
    public static final String SETTINGS = "settings";

    /** App that owns {@link #SETTINGS}. */
    public static final String SETTINGS_OWNER = "catapulsar";

    private CatacumbasTopics() {
    }

    /**
     * Returns an app's namespace.
     *
     * @param app app name, e.g. {@code catapulsar}
     * @return {@code catacumbas69/<app>}
     */
    public static String namespace(String app) {
        return TENANT + "/" + app;
    }

    /**
     * Returns a topic in an app's namespace.
     *
     * @param app app name
     * @param localName topic name inside the namespace, e.g. {@link #EVENTS}
     * @return {@code persistent://catacumbas69/<app>/<localName>}
     */
    public static String topic(String app, String localName) {
        return "persistent://" + namespace(app) + "/" + localName;
    }

    /**
     * Returns an app's event topic.
     *
     * @param app app name
     * @return {@code persistent://catacumbas69/<app>/events}
     */
    public static String events(String app) {
        return topic(app, EVENTS);
    }

    /**
     * Returns the shared settings topic.
     *
     * @return {@code persistent://catacumbas69/catapulsar/settings}
     */
    public static String settings() {
        return topic(SETTINGS_OWNER, SETTINGS);
    }
}
