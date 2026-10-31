package com.catacumbas69.catametricas.service;

import com.catacumbas69.catametricas.model.TopicSnapshot;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What one collection writes: the traffic of every topic read and the topics that disappeared.
 *
 * @param traffic one entry per topic read
 * @param deactivate topics no longer listed by Pulsar; their history is kept, they leave the current state
 */
public record CollectionPlan(List<TopicTraffic> traffic, List<String> deactivate) {

    /**
     * Compares a reading with the previous snapshots.
     *
     * @param previous latest snapshot of every topic seen before, by full topic name
     * @param reading what was read now
     * @return the plan; on the very first collection (no snapshots) all traffic is zero (baseline)
     */
    public static CollectionPlan of(Map<String, TopicSnapshot> previous, PulsarReading reading) {
        boolean baseline = previous.isEmpty();
        List<TopicTraffic> traffic = reading.samples().stream()
                .map(sample -> trafficOf(previous.get(sample.topic()), sample, baseline))
                .toList();

        Set<String> read = reading.samples().stream().map(TopicSample::topic).collect(Collectors.toSet());
        List<String> gone = previous.values().stream()
                .filter(TopicSnapshot::active)
                .filter(snapshot -> !read.contains(snapshot.topic()))
                .filter(snapshot -> !reading.failedTopics().contains(snapshot.topic()))
                .filter(snapshot -> reading.listedNamespaces().contains(namespaceOf(snapshot)))
                .map(TopicSnapshot::topic)
                .sorted()
                .toList();
        return new CollectionPlan(traffic, gone);
    }

    private static TopicTraffic trafficOf(TopicSnapshot before, TopicSample now, boolean baseline) {
        if (before == null) {
            return new TopicTraffic(now,
                    CounterDelta.between(null, now.msgInCounter(), baseline),
                    CounterDelta.between(null, now.msgOutCounter(), baseline),
                    CounterDelta.between(null, now.bytesInCounter(), baseline),
                    CounterDelta.between(null, now.bytesOutCounter(), baseline));
        }
        return new TopicTraffic(now,
                CounterDelta.between(before.msgInCounter(), now.msgInCounter(), baseline),
                CounterDelta.between(before.msgOutCounter(), now.msgOutCounter(), baseline),
                CounterDelta.between(before.bytesInCounter(), now.bytesInCounter(), baseline),
                CounterDelta.between(before.bytesOutCounter(), now.bytesOutCounter(), baseline));
    }

    private static String namespaceOf(TopicSnapshot snapshot) {
        return snapshot.tenant() + "/" + snapshot.namespace();
    }
}
