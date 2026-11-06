package com.catacumbas69.catacommons.consumers;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import org.apache.pulsar.client.api.Consumer;

/**
 * Pause and resume of one consumer of the app (orch.md decisions 66, 67). The app attaches its Pulsar consumer
 * every time it subscribes; the gate keeps the state across subscriptions.
 *
 * <ul>
 *   <li>{@code PAUSED_AUTO}: the app paused it because a dependency (e.g. MongoDB) is down; it is resumed
 *       automatically when the dependencies answer again.</li>
 *   <li>{@code PAUSED_MANUAL}: someone paused it by hand; only a manual resume ends it.</li>
 * </ul>
 *
 * <p>A paused Pulsar consumer stops fetching messages; those already received are negatively acknowledged by the
 * app and come back after the pause, with one more delivery counted.
 */
public final class ConsumerGate {

    /** Not subscribed yet. */
    public static final String STARTING = "STARTING";
    /** Receiving messages. */
    public static final String RUNNING = "RUNNING";
    /** Paused because a dependency is down; resumed automatically. */
    public static final String PAUSED_AUTO = "PAUSED_AUTO";
    /** Paused by hand; resumed only by hand. */
    public static final String PAUSED_MANUAL = "PAUSED_MANUAL";

    /** Receives every change, to publish it. */
    @FunctionalInterface
    public interface Listener {
        /**
         * Called after a change.
         *
         * @param gate the gate
         * @param action {@code paused} or {@code resumed}
         * @param actor who made it
         * @param automatic whether the app made it on its own
         */
        void changed(ConsumerGate gate, String action, String actor, boolean automatic);
    }

    private final String name;
    private final String topic;
    private final String subscription;
    private final Set<String> dependencies;
    private final String app;
    private final Clock clock;
    private final Listener listener;

    private Consumer<?> consumer;
    private String state = STARTING;
    private String reason;
    private String changedBy;
    private Instant since;
    private int maxDeliveries;
    private String deadLetterTopic;

    ConsumerGate(String name, String topic, String subscription, Set<String> dependencies, String app, Clock clock,
                 Listener listener) {
        this.name = name;
        this.topic = topic;
        this.subscription = subscription;
        this.dependencies = Set.copyOf(dependencies);
        this.app = app;
        this.clock = clock;
        this.listener = listener;
        this.since = clock.instant();
        this.changedBy = app;
    }

    /**
     * Returns the consumer's name inside the app.
     *
     * @return e.g. {@code audit}
     */
    public String name() {
        return name;
    }

    /**
     * Returns the dependencies that must answer before an automatic resume.
     *
     * @return probe names, e.g. {@code mongodb}
     */
    public Set<String> dependencies() {
        return dependencies;
    }

    /**
     * Uses a newly subscribed consumer, paused straight away if the gate is paused.
     *
     * @param subscribed the consumer
     * @param deadLetters its DLQ, or {@code null}
     * @param deliveries deliveries before the DLQ
     */
    public synchronized void attach(Consumer<?> subscribed, String deadLetters, int deliveries) {
        this.consumer = subscribed;
        this.deadLetterTopic = deadLetters;
        this.maxDeliveries = deliveries;
        if (isPaused()) {
            subscribed.pause();
        } else {
            set(RUNNING, null, changedBy);
        }
    }

    /** Forgets the consumer (closed, to subscribe again); the pause state is kept. */
    public synchronized void detach() {
        this.consumer = null;
        if (!isPaused()) {
            set(STARTING, null, changedBy);
        }
    }

    /**
     * Pauses because a dependency is down. Does nothing if already paused.
     *
     * @param why e.g. {@code MongoDB unavailable: …}
     * @return {@code true} if it paused now
     */
    public boolean pauseAutomatically(String why) {
        synchronized (this) {
            if (isPaused()) {
                return false;
            }
            set(PAUSED_AUTO, why, app);
            pauseConsumer();
        }
        listener.changed(this, "paused", app, true);
        return true;
    }

    /**
     * Resumes after an automatic pause. Does nothing after a manual pause.
     *
     * @return {@code true} if it resumed now
     */
    public boolean resumeAutomatically() {
        synchronized (this) {
            if (!PAUSED_AUTO.equals(state)) {
                return false;
            }
            resumeConsumer();
            set(consumer == null ? STARTING : RUNNING, null, app);
        }
        listener.changed(this, "resumed", app, true);
        return true;
    }

    /**
     * Pauses by hand; only {@link #resumeManually(String)} ends it.
     *
     * @param actor who asked
     * @return {@code true} if the state changed
     */
    public boolean pauseManually(String actor) {
        synchronized (this) {
            if (PAUSED_MANUAL.equals(state)) {
                return false;
            }
            set(PAUSED_MANUAL, "paused by hand", actor);
            pauseConsumer();
        }
        listener.changed(this, "paused", actor, false);
        return true;
    }

    /**
     * Resumes by hand, after any pause.
     *
     * @param actor who asked
     * @return {@code true} if the state changed
     */
    public boolean resumeManually(String actor) {
        synchronized (this) {
            if (!isPaused()) {
                return false;
            }
            resumeConsumer();
            set(consumer == null ? STARTING : RUNNING, null, actor);
        }
        listener.changed(this, "resumed", actor, false);
        return true;
    }

    /**
     * Tells whether messages must not be handled now.
     *
     * @return {@code true} while paused
     */
    public synchronized boolean isPaused() {
        return PAUSED_AUTO.equals(state) || PAUSED_MANUAL.equals(state);
    }

    /**
     * Returns the state.
     *
     * @return one of the constants of this class
     */
    public synchronized String state() {
        return state;
    }

    /**
     * Returns why it is paused.
     *
     * @return the reason, or {@code null}
     */
    public synchronized String reason() {
        return reason;
    }

    /**
     * Describes the gate for the status topic.
     *
     * @param instance this replica
     * @param lastCheck the replica's last connection check, or {@code null}
     * @return the status
     */
    public synchronized ConsumerStatus status(String instance, ConnectionCheck lastCheck) {
        return new ConsumerStatus(app, instance, name, topic, subscription, state, reason, changedBy,
                since.toString(), clock.instant().toString(), deadLetterTopic, maxDeliveries, lastCheck);
    }

    private void set(String newState, String why, String actor) {
        if (!newState.equals(state)) {
            since = clock.instant();
        }
        state = newState;
        reason = why;
        changedBy = actor;
    }

    private void pauseConsumer() {
        if (consumer != null) {
            consumer.pause();
        }
    }

    private void resumeConsumer() {
        if (consumer != null) {
            consumer.resume();
        }
    }
}
