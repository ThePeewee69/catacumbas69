package com.catacumbas69.catacommons.pulsar;

import com.catacumbas69.catacommons.CatacommonsProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.ScheduledExecutorService;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.Schema;

/**
 * Creates {@link RetryingSender}s with this app's retry settings ({@code catacommons.events.*}). The
 * caller owns each sender and must close it on shutdown, e.g. with {@code @Bean(destroyMethod = "close")}.
 */
public class RetryingSenderFactory {

    private final PulsarClient client;
    private final CatacommonsProperties.Events settings;
    private final ScheduledExecutorService scheduler;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    /**
     * Creates the factory.
     *
     * @param client Pulsar client
     * @param properties catacommons configuration
     * @param scheduler runs retries
     * @param objectMapper renders given-up messages as JSON for the log
     * @param meterRegistry metrics registry
     */
    public RetryingSenderFactory(PulsarClient client, CatacommonsProperties properties,
                                 ScheduledExecutorService scheduler, ObjectMapper objectMapper,
                                 MeterRegistry meterRegistry) {
        this.client = client;
        this.settings = properties.events();
        this.scheduler = scheduler;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Creates a sender for one topic.
     *
     * @param <T> value type
     * @param schema value schema
     * @param topic fully-qualified topic
     * @return the sender; nothing connects until the first message
     */
    public <T> RetryingSender<T> create(Schema<T> schema, String topic) {
        return new RetryingSender<>(client, schema, topic, settings.retryDelays(), settings.sendTimeout(),
                scheduler, this::json, meterRegistry);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return String.valueOf(value);
        }
    }
}
