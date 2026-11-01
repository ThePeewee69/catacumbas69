package com.catacumbas69.catametricas.service;

import com.catacumbas69.catametricas.model.TopicSnapshot;

/**
 * What happened on one topic since the previous collection.
 *
 * @param sample statistics read now
 * @param msgsIn messages published since the previous collection
 * @param msgsOut messages delivered since the previous collection
 * @param bytesIn bytes published since the previous collection
 * @param bytesOut bytes delivered since the previous collection
 */
public record TopicTraffic(TopicSample sample, long msgsIn, long msgsOut, long bytesIn, long bytesOut) {

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
        if (before == null) {
            return new TopicTraffic(now,
                    CounterDelta.between(null, now.msgInCounter(), baseline),
                    CounterDelta.between(null, now.msgOutCounter(), baseline),
                    CounterDelta.between(null, now.bytesInCounter(), baseline),
                    CounterDelta.between(null, now.bytesOutCounter(), baseline));
        }
        return new TopicTraffic(now,
                CounterDelta.between(before.msgInCounter(), now.msgInCounter(), false),
                CounterDelta.between(before.msgOutCounter(), now.msgOutCounter(), false),
                CounterDelta.between(before.bytesInCounter(), now.bytesInCounter(), false),
                CounterDelta.between(before.bytesOutCounter(), now.bytesOutCounter(), false));
    }
}
