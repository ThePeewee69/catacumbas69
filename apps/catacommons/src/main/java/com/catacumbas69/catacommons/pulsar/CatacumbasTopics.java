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

    /** Local name of Cataudit's input topic, fed by the {@code audit-<app>} bridges (ADR 0009). */
    public static final String AUDIT = "audit";

    /** App that owns {@link #AUDIT}. */
    public static final String AUDIT_OWNER = "cataudit";

    /** Local name of an app's compacted consumer status topic (orch.md decision 67). */
    public static final String CONSUMERS = "consumers";

    /** Local name of an app's consumer commands topic (orch.md decision 67). */
    public static final String CONSUMER_COMMANDS = "consumer-commands";

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

    /**
     * Returns an app's consumer status topic.
     *
     * @param app app name
     * @return {@code persistent://catacumbas69/<app>/consumers}
     */
    public static String consumers(String app) {
        return topic(app, CONSUMERS);
    }

    /**
     * Returns an app's consumer commands topic.
     *
     * @param app app name
     * @return {@code persistent://catacumbas69/<app>/consumer-commands}
     */
    public static String consumerCommands(String app) {
        return topic(app, CONSUMER_COMMANDS);
    }

    /**
     * Returns Cataudit's input topic.
     *
     * @return {@code persistent://catacumbas69/cataudit/audit}
     */
    public static String audit() {
        return topic(AUDIT_OWNER, AUDIT);
    }
}
