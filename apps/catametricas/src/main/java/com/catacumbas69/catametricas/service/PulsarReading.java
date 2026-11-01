package com.catacumbas69.catametricas.service;

import java.util.List;
import java.util.Set;

/**
 * Everything read from Pulsar for one namespace in one task.
 *
 * @param namespace full namespace, {@code tenant/namespace}; its topic list was read, so topics of it that were
 *        not listed are gone
 * @param samples statistics of every topic that could be read
 * @param failedTopics topics that were listed but whose statistics could not be read
 */
public record PulsarReading(String namespace, List<TopicSample> samples, Set<String> failedTopics) {
}
