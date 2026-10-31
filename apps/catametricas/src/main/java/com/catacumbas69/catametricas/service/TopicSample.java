package com.catacumbas69.catametricas.service;

import java.util.Collection;
import java.util.List;
import org.apache.pulsar.common.policies.data.SubscriptionStats;
import org.apache.pulsar.common.policies.data.TopicStats;

/**
 * Statistics of one topic read from Pulsar in one collection.
 *
 * @param topic full topic name
 * @param names parts of the name
 * @param msgInCounter published messages since the topic was loaded on its broker
 * @param msgOutCounter delivered messages since the topic was loaded
 * @param bytesInCounter published bytes since the topic was loaded
 * @param bytesOutCounter delivered bytes since the topic was loaded
 * @param msgRateIn published messages per second
 * @param msgRateOut delivered messages per second
 * @param throughputIn published bytes per second
 * @param throughputOut delivered bytes per second
 * @param backlogMsgs messages waiting, summed over subscriptions
 * @param backlogBytes bytes waiting
 * @param storageSize bytes stored
 * @param subscriptions number of subscriptions
 * @param producers number of producers
 * @param consumers number of consumers over all subscriptions
 */
public record TopicSample(
        String topic,
        TopicNames names,
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

    /**
     * Converts Pulsar's statistics.
     *
     * @param topic full topic name
     * @param stats statistics returned by the admin API
     * @return the sample
     */
    public static TopicSample of(String topic, TopicStats stats) {
        Collection<? extends SubscriptionStats> subs = stats.getSubscriptions() == null
                ? List.of() : stats.getSubscriptions().values();
        long backlog = subs.stream().mapToLong(SubscriptionStats::getMsgBacklog).sum();
        int consumers = subs.stream()
                .mapToInt(sub -> sub.getConsumers() == null ? 0 : sub.getConsumers().size())
                .sum();
        int producers = stats.getPublishers() == null ? 0 : stats.getPublishers().size();
        return new TopicSample(topic, TopicNames.parse(topic),
                stats.getMsgInCounter(), stats.getMsgOutCounter(),
                stats.getBytesInCounter(), stats.getBytesOutCounter(),
                stats.getMsgRateIn(), stats.getMsgRateOut(),
                stats.getMsgThroughputIn(), stats.getMsgThroughputOut(),
                backlog, stats.getBacklogSize(), stats.getStorageSize(),
                subs.size(), producers, consumers);
    }
}
