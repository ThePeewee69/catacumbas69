package com.catacumbas69.cataudit.service;

import com.catacumbas69.catacommons.CatacommonsScheduler;
import com.catacumbas69.catacommons.events.CloudEvent;
import com.catacumbas69.catacommons.pulsar.CatacumbasTopics;
import com.catacumbas69.cataudit.config.CatauditProperties;
import com.catacumbas69.cataudit.model.AuditEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.SubscriptionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code catacumbas69/cataudit/audit} and stores every event (ADR 0009).
 *
 * <p>The subscription is {@code Shared}, so every replica takes a share (ADR 0012). A message is acknowledged
 * once stored, or when it was already stored (same id); it is negatively acknowledged, and so delivered again
 * later, when MongoDB fails. A message that is not a CloudEvent is logged at {@code ERROR} and acknowledged so
 * it cannot block the topic. Subscribing happens in the background and is retried, so Cataudit starts while
 * Pulsar is down. Metrics: {@code cataudit.events{outcome=stored|duplicate|invalid|retry}}.
 */
@Component
public class AuditConsumer implements ApplicationListener<ApplicationReadyEvent>, DisposableBean {

    private static final Logger LOG = LoggerFactory.getLogger(AuditConsumer.class);

    /** What happened to one message. */
    enum Outcome {
        /** Stored now. */
        STORED,
        /** Already stored (same id). */
        DUPLICATE,
        /** Not a CloudEvent with an id; dropped. */
        INVALID,
        /** Not stored (MongoDB failed, or any other error); delivered again later, without limit. */
        RETRY
    }

    private final PulsarClient client;
    private final CatauditProperties.Consumer settings;
    private final AuditStore store;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final CatacommonsScheduler scheduler;
    private final Counter stored;
    private final Counter duplicates;
    private final Counter invalid;
    private final Counter retries;
    private final Counter errors;
    private volatile Consumer<byte[]> consumer;
    private volatile String lastError;
    private volatile boolean stopping;

    /**
     * Creates the consumer; nothing connects until the application is ready.
     *
     * @param client Pulsar client
     * @param properties application properties
     * @param store audit storage
     * @param objectMapper reads the CloudEvents
     * @param clock time source for {@code receivedAt}
     * @param scheduler runs subscription retries
     * @param meterRegistry metrics registry
     */
    public AuditConsumer(PulsarClient client, CatauditProperties properties, AuditStore store,
                         ObjectMapper objectMapper, Clock clock, CatacommonsScheduler scheduler,
                         MeterRegistry meterRegistry) {
        this.client = client;
        this.settings = properties.consumer();
        this.store = store;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.scheduler = scheduler;
        this.stored = counter(meterRegistry, "stored");
        this.duplicates = counter(meterRegistry, "duplicate");
        this.invalid = counter(meterRegistry, "invalid");
        this.retries = counter(meterRegistry, "retry");
        this.errors = counter(meterRegistry, "error");
    }

    /**
     * Subscribes in the background.
     *
     * @param event the application is ready
     */
    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        subscribe();
    }

    /**
     * Tells whether the consumer is subscribed and connected.
     *
     * @return {@code true} when messages are being received
     */
    public boolean isConnected() {
        Consumer<byte[]> current = consumer;
        return current != null && current.isConnected();
    }

    /**
     * Returns why the last subscription attempt failed.
     *
     * @return the message, or {@code null}
     */
    public String lastError() {
        return lastError;
    }

    /** Closes the consumer on shutdown; unacknowledged messages are delivered again to another replica. */
    @Override
    public void destroy() {
        stopping = true;
        Consumer<byte[]> current = consumer;
        if (current != null) {
            try {
                current.close();
            } catch (PulsarClientException e) {
                LOG.warn("Audit consumer not closed cleanly: {}", e.getMessage());
            }
        }
    }

    private void subscribe() {
        if (stopping) {
            return;
        }
        client.newConsumer(Schema.BYTES)
                .topic(CatacumbasTopics.audit())
                .subscriptionName(settings.subscription())
                .subscriptionType(SubscriptionType.Shared)
                .receiverQueueSize(settings.receiverQueueSize())
                .negativeAckRedeliveryDelay(settings.negativeAckRedeliveryDelay().toMillis(), TimeUnit.MILLISECONDS)
                .messageListener(this::onMessage)
                .subscribeAsync()
                .whenComplete((subscribed, error) -> {
                    if (error == null) {
                        consumer = subscribed;
                        lastError = null;
                        LOG.info("Consuming {} as {}", CatacumbasTopics.audit(), settings.subscription());
                    } else {
                        lastError = error.getMessage();
                        LOG.warn("Audit topic not subscribed, retrying in {} s: {}",
                                settings.retryInterval().toSeconds(), error.getMessage());
                        retryLater();
                    }
                });
    }

    private void retryLater() {
        try {
            scheduler.executor().schedule(this::subscribe, settings.retryInterval().toMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            LOG.debug("Audit subscription not retried, shutting down");
        }
    }

    /** Every message is acknowledged or negatively acknowledged, whatever happens while handling it. */
    private void onMessage(Consumer<byte[]> source, Message<byte[]> message) {
        Outcome outcome;
        try {
            outcome = handle(message.getValue());
        } catch (RuntimeException e) {
            errors.increment();
            LOG.error("Audit message {} not handled, will be delivered again", message.getMessageId(), e);
            outcome = Outcome.RETRY;
        }
        if (outcome == Outcome.RETRY) {
            source.negativeAcknowledge(message);
        } else {
            source.acknowledgeAsync(message);
        }
    }

    /**
     * Stores one message.
     *
     * @param payload the message value, a CloudEvent in JSON
     * @return what happened
     */
    Outcome handle(byte[] payload) {
        Optional<AuditEvent> event;
        try {
            event = AuditEvents.from(objectMapper.readValue(payload, CloudEvent.class), clock.instant());
        } catch (IOException e) {
            event = Optional.empty();
        }
        if (event.isEmpty()) {
            invalid.increment();
            LOG.error("Audit message is not a CloudEvent with an id, dropped: {}",
                    new String(payload, StandardCharsets.UTF_8));
            return Outcome.INVALID;
        }
        try {
            if (store.insert(event.get())) {
                stored.increment();
                return Outcome.STORED;
            }
            duplicates.increment();
            return Outcome.DUPLICATE;
        } catch (DataAccessException e) {
            retries.increment();
            LOG.warn("Audit event {} not stored, will be delivered again: {}", event.get().id(), e.getMessage());
            return Outcome.RETRY;
        } catch (RuntimeException e) {
            // not MongoDB being down: it will not fix itself, so log it in full every time it comes back
            errors.increment();
            LOG.error("Audit event {} could not be stored, will be delivered again", event.get().id(), e);
            return Outcome.RETRY;
        }
    }

    private static Counter counter(MeterRegistry registry, String outcome) {
        return Counter.builder("cataudit.events")
                .description("Audit messages received, by outcome")
                .tag("outcome", outcome)
                .register(registry);
    }
}
