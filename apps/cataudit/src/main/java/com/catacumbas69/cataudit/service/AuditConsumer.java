package com.catacumbas69.cataudit.service;

import com.catacumbas69.catacommons.CatacommonsProperties;
import com.catacumbas69.catacommons.CatacommonsScheduler;
import com.catacumbas69.catacommons.consumers.ConnectionProbe;
import com.catacumbas69.catacommons.consumers.ConsumerControl;
import com.catacumbas69.catacommons.consumers.ConsumerGate;
import com.catacumbas69.catacommons.consumers.DeadLetterWriter;
import com.catacumbas69.catacommons.consumers.DeadLetters;
import com.catacumbas69.catacommons.events.CloudEvent;
import com.catacumbas69.catacommons.pulsar.CatacumbasTopics;
import com.catacumbas69.catacommons.settings.SharedSettings;
import com.catacumbas69.cataudit.config.CatauditProperties;
import com.catacumbas69.cataudit.model.AuditEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.time.Clock;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.SubscriptionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code catacumbas69/cataudit/audit} and stores every event (ADR 0009).
 *
 * <p>The subscription is {@code Shared}, so every replica takes a share (ADR 0012). A message is acknowledged
 * once stored, or when it was already stored (same id). ADR 0015:
 *
 * <ul>
 *   <li>a message that fails to be stored for its own reasons is negatively acknowledged; after
 *       {@code dlq.max-deliveries} deliveries Pulsar moves it to {@code audit-cataudit-DLQ};</li>
 *   <li>a message that is not a CloudEvent goes straight to that DLQ;</li>
 *   <li>when MongoDB is unavailable the consumer <b>pauses</b> (the message is negatively acknowledged once) and
 *       resumes by itself when MongoDB answers a ping; it can also be paused and resumed by hand
 *       ({@link ConsumerControl}).</li>
 * </ul>
 *
 * <p>Subscribing happens in the background and is retried, so Cataudit starts while Pulsar is down; it subscribes
 * again when {@code dlq.max-deliveries} changes. Metrics:
 * {@code cataudit.events{outcome=stored|duplicate|invalid|retry|error}}.
 */
@Component
public class AuditConsumer implements ApplicationListener<ApplicationReadyEvent>, DisposableBean {

    /** Name of this consumer in the Consumers panel. */
    public static final String NAME = "audit";

    private static final Logger LOG = LoggerFactory.getLogger(AuditConsumer.class);

    /** What happened to one message. */
    enum Outcome {
        /** Stored now. */
        STORED,
        /** Already stored (same id). */
        DUPLICATE,
        /** Not a CloudEvent with an id; sent straight to the DLQ. */
        DEAD_LETTER,
        /** Not stored; delivered again later, counting towards the DLQ (MongoDB down pauses the consumer). */
        RETRY
    }

    private final PulsarClient client;
    private final CatauditProperties.Consumer settings;
    private final AuditStore store;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final CatacommonsScheduler scheduler;
    private final ConsumerControl control;
    private final ConsumerGate gate;
    private final DeadLetterWriter deadLetters;
    private final SharedSettings sharedSettings;
    private final int defaultMaxDeliveries;
    private final Counter stored;
    private final Counter duplicates;
    private final Counter invalid;
    private final Counter retries;
    private final Counter errors;
    private volatile Consumer<byte[]> consumer;
    private volatile int maxDeliveries;
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
     * @param control pause, resume and status of the consumer
     * @param deadLetters sends invalid messages straight to the DLQ
     * @param sharedSettings source of {@code dlq.max-deliveries}, if available
     * @param commons catacommons configuration, for the default number of deliveries
     */
    public AuditConsumer(PulsarClient client, CatauditProperties properties, AuditStore store,
                         ObjectMapper objectMapper, Clock clock, CatacommonsScheduler scheduler,
                         MeterRegistry meterRegistry, ConsumerControl control, DeadLetterWriter deadLetters,
                         ObjectProvider<SharedSettings> sharedSettings, CatacommonsProperties commons) {
        this.client = client;
        this.settings = properties.consumer();
        this.store = store;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.scheduler = scheduler;
        this.control = control;
        this.deadLetters = deadLetters;
        this.sharedSettings = sharedSettings.getIfAvailable();
        this.defaultMaxDeliveries = commons.consumers().maxDeliveries();
        this.maxDeliveries = DeadLetters.maxDeliveries(this.sharedSettings, defaultMaxDeliveries);
        this.gate = control.register(NAME, CatacumbasTopics.audit(), settings.subscription(),
                Set.of(ConnectionProbe.MONGODB));
        this.stored = counter(meterRegistry, "stored");
        this.duplicates = counter(meterRegistry, "duplicate");
        this.invalid = counter(meterRegistry, "invalid");
        this.retries = counter(meterRegistry, "retry");
        this.errors = counter(meterRegistry, "error");
    }

    /**
     * Subscribes in the background, and follows {@code dlq.max-deliveries}.
     *
     * @param event the application is ready
     */
    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (sharedSettings != null) {
            sharedSettings.listen(DeadLetters.MAX_DELIVERIES_KEY, value -> onMaxDeliveries(
                    value == null ? defaultMaxDeliveries : DeadLetters.parse(value, defaultMaxDeliveries)));
        }
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

    /**
     * Returns the consumer's pause state.
     *
     * @return the gate
     */
    public ConsumerGate gate() {
        return gate;
    }

    /** Closes the consumer on shutdown; unacknowledged messages are delivered again to another replica. */
    @Override
    public void destroy() {
        stopping = true;
        Consumer<byte[]> current = consumer;
        if (current != null) {
            current.closeAsync();
        }
    }

    /** The policy is fixed when subscribing, so a new number of deliveries means subscribing again. */
    private void onMaxDeliveries(int deliveries) {
        if (deliveries == maxDeliveries) {
            return;
        }
        LOG.info("Deliveries before the DLQ changed from {} to {}: subscribing again", maxDeliveries, deliveries);
        maxDeliveries = deliveries;
        Consumer<byte[]> current = consumer;
        if (current == null) {
            return;
        }
        consumer = null;
        gate.detach();
        current.closeAsync().whenComplete((closed, error) -> subscribe());
    }

    private void subscribe() {
        if (stopping) {
            return;
        }
        int deliveries = maxDeliveries;
        String topic = CatacumbasTopics.audit();
        client.newConsumer(Schema.BYTES)
                .topic(topic)
                .subscriptionName(settings.subscription())
                .consumerName(control.consumerName())
                .subscriptionType(SubscriptionType.Shared)
                .receiverQueueSize(settings.receiverQueueSize())
                .negativeAckRedeliveryDelay(settings.negativeAckRedeliveryDelay().toMillis(), TimeUnit.MILLISECONDS)
                .deadLetterPolicy(DeadLetters.policy(topic, settings.subscription(), deliveries))
                .messageListener(this::onMessage)
                .subscribeAsync()
                .whenComplete((subscribed, error) -> {
                    if (error == null) {
                        consumer = subscribed;
                        lastError = null;
                        gate.attach(subscribed, DeadLetters.topicOf(topic, settings.subscription()), deliveries);
                        LOG.info("Consuming {} as {} ({} deliveries before the DLQ)", topic, settings.subscription(),
                                deliveries);
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

    /** Every message is acknowledged, negatively acknowledged or dead-lettered, whatever happens. */
    private void onMessage(Consumer<byte[]> source, Message<byte[]> message) {
        if (gate.isPaused()) {
            // received before the pause took effect; it comes back after the pause
            source.negativeAcknowledge(message);
            return;
        }
        Outcome outcome;
        try {
            outcome = handle(message.getValue());
        } catch (RuntimeException e) {
            errors.increment();
            LOG.error("Audit message {} not handled, will be delivered again", message.getMessageId(), e);
            outcome = Outcome.RETRY;
        }
        switch (outcome) {
            case RETRY -> source.negativeAcknowledge(message);
            case DEAD_LETTER -> deadLetters.send(CatacumbasTopics.audit(), settings.subscription(), message,
                            "not a CloudEvent with an id")
                    .whenComplete((id, error) -> {
                        if (error == null) {
                            source.acknowledgeAsync(message);
                        } else {
                            LOG.warn("Audit message {} not sent to the DLQ, will be delivered again: {}",
                                    message.getMessageId(), error.getMessage());
                            source.negativeAcknowledge(message);
                        }
                    });
            default -> source.acknowledgeAsync(message);
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
            LOG.error("Audit message is not a CloudEvent with an id, sent to the DLQ");
            return Outcome.DEAD_LETTER;
        }
        try {
            if (store.insert(event.get())) {
                stored.increment();
                return Outcome.STORED;
            }
            duplicates.increment();
            return Outcome.DUPLICATE;
        } catch (DataAccessResourceFailureException | TransientDataAccessException e) {
            retries.increment();
            if (gate.pauseAutomatically("MongoDB unavailable: " + e.getMessage())) {
                LOG.warn("MongoDB unavailable, audit consumer paused until it answers: {}", e.getMessage());
            }
            return Outcome.RETRY;
        } catch (RuntimeException e) {
            // not MongoDB being down: this message fails on its own and counts towards the DLQ
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
