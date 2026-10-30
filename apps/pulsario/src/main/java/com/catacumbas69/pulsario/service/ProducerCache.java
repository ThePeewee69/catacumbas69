package com.catacumbas69.pulsario.service;

import com.catacumbas69.pulsario.config.PulsarioProperties;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Reuses one producer per topic instead of creating one per request (PERF-6). Producers unused for
 * {@code pulsario.pulsar.producer-idle-timeout} are closed; the cache never holds more than
 * {@code max-cached-producers} (the least recently used one is closed first).
 */
@Component
public class ProducerCache {

    private static final Logger LOG = LoggerFactory.getLogger(ProducerCache.class);

    private final PulsarClient client;
    private final Clock clock;
    private final Duration idleTimeout;
    private final Duration sendTimeout;
    private final int maxProducers;
    private final Map<String, Entry> producers = new ConcurrentHashMap<>();

    /**
     * Creates the cache.
     *
     * @param client shared Pulsar client
     * @param properties application properties
     * @param clock time source for idle tracking
     */
    public ProducerCache(PulsarClient client, PulsarioProperties properties, Clock clock) {
        this.client = client;
        this.clock = clock;
        this.idleTimeout = properties.pulsar().producerIdleTimeout();
        this.sendTimeout = properties.pulsar().operationTimeout();
        this.maxProducers = properties.pulsar().maxCachedProducers();
    }

    /**
     * Returns the producer for a topic, creating it on first use.
     *
     * @param topic fully-qualified topic
     * @return a connected producer
     * @throws PulsarClientException if the producer cannot be created
     */
    public Producer<byte[]> get(String topic) throws PulsarClientException {
        Entry existing = producers.get(topic);
        if (existing != null && existing.producer().isConnected()) {
            producers.put(topic, existing.touch(clock.instant()));
            return existing.producer();
        }
        if (existing != null) {
            remove(topic);
        }
        evictIfFull();
        // Batching off: the UI shows the id of each message right away.
        Producer<byte[]> producer = client.newProducer()
                .topic(topic)
                .enableBatching(false)
                .sendTimeout((int) sendTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .create();
        Entry created = new Entry(producer, clock.instant());
        Entry raced = producers.putIfAbsent(topic, created);
        if (raced != null) {
            producer.closeAsync();
            return raced.producer();
        }
        return producer;
    }

    /**
     * Closes and forgets the producer of a topic, e.g. after the topic was deleted.
     *
     * @param topic fully-qualified topic
     */
    public void remove(String topic) {
        Entry entry = producers.remove(topic);
        if (entry != null) {
            entry.producer().closeAsync();
        }
    }

    /** Closes producers that have been idle longer than the idle timeout. */
    @Scheduled(fixedDelayString = "PT1M")
    public void evictIdle() {
        Instant cutoff = clock.instant().minus(idleTimeout);
        producers.forEach((topic, entry) -> {
            if (entry.lastUsed().isBefore(cutoff)) {
                LOG.debug("Closing idle producer for {}", topic);
                remove(topic);
            }
        });
    }

    /**
     * Returns the number of cached producers.
     *
     * @return cache size
     */
    public int size() {
        return producers.size();
    }

    /** Closes every producer on shutdown. */
    @PreDestroy
    public void closeAll() {
        producers.keySet().forEach(this::remove);
    }

    private void evictIfFull() {
        while (producers.size() >= maxProducers) {
            producers.entrySet().stream()
                    .min(Comparator.comparing(entry -> entry.getValue().lastUsed()))
                    .ifPresent(oldest -> remove(oldest.getKey()));
        }
    }

    private record Entry(Producer<byte[]> producer, Instant lastUsed) {

        Entry touch(Instant now) {
            return new Entry(producer, now);
        }
    }
}
