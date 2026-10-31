package com.catacumbas69.catametricas.service;

import java.util.List;
import java.util.Set;

/**
 * Everything read from Pulsar in one collection.
 *
 * @param samples statistics of every topic that could be read
 * @param listedNamespaces namespaces ({@code tenant/namespace}) whose topic list was read; only topics of
 *        these namespaces can be marked as deleted
 * @param failedTopics topics that were listed but whose statistics could not be read
 */
public record PulsarReading(List<TopicSample> samples, Set<String> listedNamespaces, Set<String> failedTopics) {
}
