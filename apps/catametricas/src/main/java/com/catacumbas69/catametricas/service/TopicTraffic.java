package com.catacumbas69.catametricas.service;

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
}
