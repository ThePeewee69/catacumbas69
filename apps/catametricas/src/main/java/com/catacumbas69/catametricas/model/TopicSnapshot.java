package com.catacumbas69.catametricas.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Latest state of one topic, stored in {@value Schema#SNAPSHOTS}. The raw Pulsar counters are kept so
 * the next collection can compute deltas, also after Catametricas restarts.
 *
 * @param topic full topic name ({@code _id})
 * @param tenant tenant name
 * @param namespace namespace name without the tenant
 * @param name local topic name, e.g. {@code orders-partition-0}
 * @param baseName local name without partition suffix, e.g. {@code orders}
 * @param active {@code false} once the topic is no longer listed by Pulsar (deleted); its history is kept
 * @param collectedAt when this state was read
 * @param msgInCounter Pulsar's published-messages counter (resets when the topic is reloaded)
 * @param msgOutCounter Pulsar's delivered-messages counter
 * @param bytesInCounter Pulsar's published-bytes counter
 * @param bytesOutCounter Pulsar's delivered-bytes counter
 * @param msgRateIn published messages per second
 * @param msgRateOut delivered messages per second
 * @param throughputIn published bytes per second
 * @param throughputOut delivered bytes per second
 * @param backlogMsgs messages waiting, summed over subscriptions
 * @param backlogBytes bytes waiting
 * @param storageSize bytes stored for the topic
 * @param subscriptions number of subscriptions
 * @param producers number of connected producers
 * @param consumers number of connected consumers, over all subscriptions
 */
@Document(collection = Schema.SNAPSHOTS)
public record TopicSnapshot(
        @Id String topic,
        String tenant,
        String namespace,
        String name,
        String baseName,
        boolean active,
        Instant collectedAt,
        long msgInCounter,
        long msgOutCounter,
        long bytesInCounter,
        long bytesOutCounter,
        double msgRateIn,
        double msgRateOut,
        double throughputIn,
        double throughputOut,
        long backlogMsgs,
        long backlogBytes,
        long storageSize,
        int subscriptions,
        int producers,
        int consumers) {
}
