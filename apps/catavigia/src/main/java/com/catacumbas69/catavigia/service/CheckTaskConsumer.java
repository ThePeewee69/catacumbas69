package com.catacumbas69.catavigia.service;

import com.catacumbas69.catacommons.CatacommonsProperties;
import com.catacumbas69.catacommons.CatacommonsScheduler;
import com.catacumbas69.catacommons.consumers.ConnectionProbe;
import com.catacumbas69.catacommons.consumers.ConsumerControl;
import com.catacumbas69.catacommons.consumers.ConsumerGate;
import com.catacumbas69.catacommons.consumers.DeadLetterWriter;
import com.catacumbas69.catacommons.consumers.DeadLetters;
import com.catacumbas69.catacommons.settings.SharedSettings;
import com.catacumbas69.catavigia.config.CatavigiaProperties;
import com.catacumbas69.catavigia.config.TasksConfig;
import com.catacumbas69.catavigia.model.CheckTask;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.SubscriptionType;
import org.apache.pulsar.client.impl.MultiplierRedeliveryBackoff;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code catacumbas69/catavigia/check-tasks} with the {@code Shared} subscription {@code catavigia}, so the
 * checks spread over the replicas (orch.md decision 75). A task is acknowledged once checked or skipped (already
 * claimed). Like the consumers of phase 7a (ADR 0015): a failed task is delivered again after a growing delay and
 * goes to {@code check-tasks-catavigia-DLQ} after {@code dlq.max-deliveries} deliveries; the consumer pauses while
 * MongoDB or Pulsar's admin API is down and resumes by itself; it can be paused, resumed and checked from
 * Catapulsar's Consumers panel. A message that is not a valid task goes straight to the DLQ.
 *
 * <p>Subscribing happens in the background and is retried, so Catavigia starts while Pulsar is down.
 */
@Component
public class CheckTaskConsumer implements ApplicationListener<ApplicationReadyEvent>, DisposableBean {

    /** Name of this consumer in the Consumers panel. */
    public static final String NAME = "check-tasks";

    private static final Logger LOG = LoggerFactory.getLogger(CheckTaskConsumer.class);

    private final PulsarClient client;
    private final CatavigiaProperties.Tasks settings;
    private final CheckRunner runner;
    private final ObjectMapper objectMapper;
    private final CatacommonsScheduler scheduler;
    private final ConsumerControl control;
    private final ConsumerGate gate;
    private final DeadLetterWriter deadLetters;
    private final SharedSettings sharedSettings;
    private final int defaultMaxDeliveries;
    private volatile Consumer<byte[]> consumer;
    private volatile int maxDeliveries;
    private volatile String lastError;
    private volatile boolean stopping;

    /**
     * Creates the consumer; nothing connects until the application is ready.
     *
     * @param client Pulsar client
     * @param properties application properties
     * @param runner runs the tasks
     * @param objectMapper reads the tasks
     * @param scheduler runs subscription retries
     * @param control pause, resume and status of the consumer
     * @param deadLetters sends invalid messages straight to the DLQ
     * @param sharedSettings source of {@code dlq.max-deliveries}, if available
     * @param commons catacommons configuration, for the default number of deliveries
     */
    public CheckTaskConsumer(PulsarClient client, CatavigiaProperties properties, CheckRunner runner,
                             ObjectMapper objectMapper, CatacommonsScheduler scheduler, ConsumerControl control,
                             DeadLetterWriter deadLetters, ObjectProvider<SharedSettings> sharedSettings,
                             CatacommonsProperties commons) {
        this.client = client;
        this.settings = properties.tasks();
        this.runner = runner;
        this.objectMapper = objectMapper;
        this.scheduler = scheduler;
        this.control = control;
        this.deadLetters = deadLetters;
        this.sharedSettings = sharedSettings.getIfAvailable();
        this.defaultMaxDeliveries = commons.consumers().maxDeliveries();
        this.maxDeliveries = DeadLetters.maxDeliveries(this.sharedSettings, defaultMaxDeliveries);
        this.gate = control.register(NAME, TasksConfig.tasksTopic(), settings.subscription(),
                Set.of(ConnectionProbe.MONGODB, ConnectionProbe.PULSAR));
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
     * @return {@code true} when tasks are being received
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

    /** Closes the consumer on shutdown; unacknowledged tasks are delivered to another instance. */
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
        String topic = TasksConfig.tasksTopic();
        client.newConsumer(Schema.BYTES)
                .topic(topic)
                .subscriptionName(settings.subscription())
                .consumerName(control.consumerName())
                .subscriptionType(SubscriptionType.Shared)
                .receiverQueueSize(settings.receiverQueueSize())
                .negativeAckRedeliveryBackoff(MultiplierRedeliveryBackoff.builder()
                        .minDelayMs(settings.retryDelay().toMillis())
                        .maxDelayMs(settings.maxRetryDelay().toMillis())
                        .multiplier(2)
                        .build())
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
                        LOG.warn("Task topic not subscribed, retrying in {} s: {}",
                                settings.subscribeRetryInterval().toSeconds(), error.getMessage());
                        retryLater();
                    }
                });
    }

    private void retryLater() {
        try {
            scheduler.executor().schedule(this::subscribe, settings.subscribeRetryInterval().toMillis(),
                    TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            LOG.debug("Task subscription not retried, shutting down");
        }
    }

    private void onMessage(Consumer<byte[]> source, Message<byte[]> message) {
        if (gate.isPaused()) {
            // received before the pause took effect; it comes back after the pause
            source.negativeAcknowledge(message);
            return;
        }
        CheckRunner.Outcome outcome = handle(message.getValue());
        switch (outcome) {
            case RETRY -> {
                pauseIfUnavailable();
                source.negativeAcknowledge(message);
            }
            case INVALID -> deadLetters.send(TasksConfig.tasksTopic(), settings.subscription(), message,
                            "not a valid check task")
                    .whenComplete((id, error) -> {
                        if (error == null) {
                            source.acknowledgeAsync(message);
                        } else {
                            LOG.warn("Task message {} not sent to the DLQ, will be delivered again: {}",
                                    message.getMessageId(), error.getMessage());
                            source.negativeAcknowledge(message);
                        }
                    });
            default -> source.acknowledgeAsync(message);
        }
    }

    /** A task that failed because a dependency is down pauses the consumer; one that failed on its own does not. */
    void pauseIfUnavailable() {
        Optional<String> reason = control.unavailable(gate);
        if (reason.isPresent() && gate.pauseAutomatically(reason.get())) {
            LOG.warn("Task consumer paused until its dependencies answer: {}", reason.get());
        }
    }

    /**
     * Runs one message.
     *
     * @param payload the message value, a {@link CheckTask} in JSON
     * @return what happened
     */
    CheckRunner.Outcome handle(byte[] payload) {
        CheckTask task;
        try {
            task = objectMapper.readValue(payload, CheckTask.class);
        } catch (IOException e) {
            LOG.error("Check task is not JSON, sent to the DLQ");
            task = null;
        }
        return runner.handle(task);
    }
}
