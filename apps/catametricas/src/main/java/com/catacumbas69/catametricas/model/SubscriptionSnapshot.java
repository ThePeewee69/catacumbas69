package com.catacumbas69.catametricas.model;

/**
 * State of one subscription of a topic at one collection, kept in the topic's snapshot ({@value Schema#SUBS}) so
 * the next collection can compute how much it delivered (orch.md decision 77).
 *
 * @param name subscription name
 * @param type {@code Shared}, {@code Failover}, {@code Key_Shared} or {@code Exclusive}
 * @param backlog messages waiting
 * @param unacked messages delivered but not acknowledged yet
 * @param consumers connected consumers
 * @param msgRateOut delivered messages per second
 * @param msgOutCounter Pulsar's delivered-messages counter of the subscription (resets when the topic is reloaded)
 * @param bytesOutCounter Pulsar's delivered-bytes counter of the subscription
 */
public record SubscriptionSnapshot(
        String name,
        String type,
        long backlog,
        long unacked,
        int consumers,
        double msgRateOut,
        long msgOutCounter,
        long bytesOutCounter) {
}
