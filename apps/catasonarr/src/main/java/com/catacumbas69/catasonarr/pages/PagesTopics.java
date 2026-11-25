package com.catacumbas69.catasonarr.pages;

import com.catacumbas69.catacommons.audit.SystemBridge;
import com.catacumbas69.catacommons.pulsar.CatacumbasTopics;

/** Names of the pages' topic, bridge and subscriptions (orch.md decision 118). */
public final class PagesTopics {

    /** The topic's name inside Catasonarr's namespace. */
    public static final String LOCAL_NAME = "pages";

    /** The consumer's name in Catapulsar's Consumers panel. */
    public static final String CONSUMER = "pages";

    /** How long a message waits on the topic for a subscription to read it: a page needs only what is new. */
    public static final int MESSAGE_TTL_SECONDS = 3600;

    private PagesTopics() {
    }

    /**
     * Returns the pages' topic.
     *
     * @param app this app's name
     * @return {@code persistent://catacumbas69/<app>/pages}
     */
    public static String topic(String app) {
        return CatacumbasTopics.topic(app, LOCAL_NAME);
    }

    /**
     * Returns the subscription of one replica.
     *
     * @param app this app's name
     * @param instance the replica (pod) name
     * @return {@code <app>-pages-<instance>}
     */
    public static String subscription(String app, String instance) {
        return app + "-pages-" + instance;
    }

    /**
     * Returns the system bridge that feeds the pages' topic from the events topic.
     *
     * @param app this app's name
     * @return the bridge {@code pages-<app>}
     */
    public static SystemBridge bridge(String app) {
        return new SystemBridge("pages-" + app, "Events of " + app + " → its open pages (system bridge)",
                CatacumbasTopics.events(app), "cataponte-pages-" + app, topic(app));
    }
}
