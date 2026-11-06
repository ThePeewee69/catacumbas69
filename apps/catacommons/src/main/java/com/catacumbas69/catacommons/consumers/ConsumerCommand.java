package com.catacumbas69.catacommons.consumers;

/**
 * A command for an app's consumers, published by Catapulsar on {@code catacumbas69/<app>/consumer-commands} and read
 * by every replica of the app (orch.md decision 67).
 *
 * @param id command id
 * @param instance target replica, or {@code *} for all
 * @param consumer target consumer, or {@code *} for all
 * @param action {@code pause}, {@code resume} or {@code check}
 * @param actor who asked, e.g. the browser address
 * @param at when it was asked (ISO-8601)
 */
public record ConsumerCommand(String id, String instance, String consumer, String action, String actor, String at) {

    /** Every replica or every consumer. */
    public static final String ALL = "*";

    /** Pause the consumer by hand; only {@code resume} ends it. */
    public static final String PAUSE = "pause";

    /** Resume a paused consumer. */
    public static final String RESUME = "resume";

    /** Check the connections now. */
    public static final String CHECK = "check";

    /**
     * Tells whether the command is meant for a replica.
     *
     * @param target the replica's instance id
     * @return {@code true} for that replica or all
     */
    public boolean forInstance(String target) {
        return ALL.equals(instance) || target.equals(instance);
    }

    /**
     * Tells whether the command is meant for a consumer.
     *
     * @param name the consumer's name
     * @return {@code true} for that consumer or all
     */
    public boolean forConsumer(String name) {
        return consumer == null || ALL.equals(consumer) || name.equals(consumer);
    }
}
