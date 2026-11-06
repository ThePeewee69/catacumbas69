package com.catacumbas69.catacommons.consumers;

/**
 * State of one consumer in one replica, published on the compacted topic {@code catacumbas69/<app>/consumers}
 * with the key {@code <instance>/<consumer>} (orch.md decision 67). A tombstone is written on shutdown.
 *
 * @param app app name
 * @param instance replica, host name plus a random suffix
 * @param consumer consumer name inside the app, e.g. {@code audit}
 * @param topic topic consumed
 * @param subscription subscription name
 * @param state {@code STARTING}, {@code RUNNING}, {@code PAUSED_AUTO} or {@code PAUSED_MANUAL}
 * @param reason why it is paused, or {@code null}
 * @param changedBy who made the last change: a browser address, or the app
 * @param since when the state began (ISO-8601)
 * @param updatedAt when this status was written (ISO-8601); an entry not updated for a while is gone
 * @param deadLetterTopic where poison messages go, or {@code null}
 * @param maxDeliveries deliveries before a message goes to the dead-letter topic
 * @param lastCheck the last connection check, or {@code null}
 */
public record ConsumerStatus(String app, String instance, String consumer, String topic, String subscription,
                             String state, String reason, String changedBy, String since, String updatedAt,
                             String deadLetterTopic, int maxDeliveries, ConnectionCheck lastCheck) {
}
