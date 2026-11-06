package com.catacumbas69.catametricas.service;

import com.catacumbas69.catametricas.model.SubscriptionSnapshot;
import com.catacumbas69.catametricas.model.TopicSnapshot;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What happened on one topic since the previous collection.
 *
 * @param sample statistics read now
 * @param msgsIn messages published since the previous collection
 * @param msgsOut messages delivered since the previous collection
 * @param bytesIn bytes published since the previous collection
 * @param bytesOut bytes delivered since the previous collection
 * @param subscriptions what each subscription delivered since the previous collection (orch.md decision 77)
 */
public record TopicTraffic(TopicSample sample, long msgsIn, long msgsOut, long bytesIn, long bytesOut,
                           List<SubscriptionTraffic> subscriptions) {

    /**
     * What one subscription delivered since the previous collection.
     *
     * @param now its state read now
     * @param msgsOut messages delivered
     * @param bytesOut bytes delivered
     */
    public record SubscriptionTraffic(SubscriptionSnapshot now, long msgsOut, long bytesOut) {
    }

    /**
     * Compares a sample with the snapshot it replaced.
     *
     * @param before the snapshot the sample replaced, or {@code null} if the topic was not seen before
     * @param now the sample
     * @param baseline {@code true} when the namespace had never been collected: an unknown topic then only sets
     *        the starting point, otherwise everything since the broker loaded it would land in one minute
     * @return the traffic in between
     */
    public static TopicTraffic between(TopicSnapshot before, TopicSample now, boolean baseline) {
        List<SubscriptionTraffic> subscriptions = subscriptions(before, now, baseline);
        if (before == null) {
            return new TopicTraffic(now,
                    CounterDelta.between(null, now.msgInCounter(), baseline),
                    CounterDelta.between(null, now.msgOutCounter(), baseline),
                    CounterDelta.between(null, now.bytesInCounter(), baseline),
                    CounterDelta.between(null, now.bytesOutCounter(), baseline),
                    subscriptions);
        }
        return new TopicTraffic(now,
                CounterDelta.between(before.msgInCounter(), now.msgInCounter(), false),
                CounterDelta.between(before.msgOutCounter(), now.msgOutCounter(), false),
                CounterDelta.between(before.bytesInCounter(), now.bytesInCounter(), false),
                CounterDelta.between(before.bytesOutCounter(), now.bytesOutCounter(), false),
                subscriptions);
    }

    /**
     * A subscription unknown to a known topic was created since, so its counters count fully. A topic seen first
     * follows the namespace's baseline, and a snapshot from before decision 77 (no subscriptions) only sets the
     * starting point.
     */
    private static List<SubscriptionTraffic> subscriptions(TopicSnapshot before, TopicSample now,
                                                           boolean baseline) {
        if (now.subs() == null) {
            return List.of();
        }
        boolean startingPoint = before == null ? baseline : before.subs() == null;
        Map<String, SubscriptionSnapshot> previous = new HashMap<>();
        if (before != null && before.subs() != null) {
            before.subs().forEach(sub -> previous.put(sub.name(), sub));
        }
        return now.subs().stream().map(sub -> {
            SubscriptionSnapshot was = previous.get(sub.name());
            return new SubscriptionTraffic(sub,
                    CounterDelta.between(was == null ? null : was.msgOutCounter(), sub.msgOutCounter(),
                            startingPoint),
                    CounterDelta.between(was == null ? null : was.bytesOutCounter(), sub.bytesOutCounter(),
                            startingPoint));
        }).toList();
    }
}
