package com.catacumbas69.catametricas.service;

import com.catacumbas69.catacommons.CatacommonsProperties;
import com.catacumbas69.catacommons.CatacommonsScheduler;
import com.catacumbas69.catacommons.consumers.ConnectionProbe;
import com.catacumbas69.catacommons.consumers.ConsumerControl;
import com.catacumbas69.catacommons.consumers.ConsumerGate;
import com.catacumbas69.catacommons.consumers.DeadLetterWriter;
import com.catacumbas69.catacommons.consumers.DeadLetters;
import com.catacumbas69.catacommons.settings.SharedSettings;
import com.catacumbas69.catametricas.config.CatametricasProperties;
import com.catacumbas69.catametricas.config.TasksConfig;
import com.catacumbas69.catametricas.model.CollectTask;
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
 * Consumes {@code catacumbas69/catametricas/collect-tasks} and runs each task with {@link StatsCollector}
 * (ADR 0012).
 *
 * <p>Every instance joins the same {@code Shared} subscription with a small receiver queue, so the tasks spread
 * over the instances. A task is acknowledged once done or skipped (already claimed). ADR 0015:
 *
 * <ul>
 *   <li>a task that fails while MongoDB and Pulsar's admin API answer is negatively acknowledged and delivered again
 *       after a wait that doubles from {@code catametricas.tasks.retry-delay} up to {@code max-retry-delay}; after
 *       {@code dlq.max-deliveries} deliveries Pulsar moves it to {@code collect-tasks-catametricas-DLQ} (this replaces
 *       "every task is run, however late");</li>
 *   <li>a task that fails because MongoDB or Pulsar's admin API is down <b>pauses</b> the consumer, which resumes by
 *       itself when they answer; it can also be paused and resumed by hand ({@link ConsumerControl});</li>
 *   <li>a message that is not a valid task goes straight to the DLQ.</li>
 * </ul>
 *
 * <p>Subscribing happens in the background and is retried, so Catametricas starts while Pulsar is down; it
 * subscribes again when {@code dlq.max-deliveries} changes.
 */
@Component
public class CollectTaskConsumer implements ApplicationListener<ApplicationReadyEvent>, DisposableBean {

    /** Name of this consumer in the Consumers panel. */
    public static final String NAME = "collect-tasks";

    private static final Logger LOG = LoggerFactory.getLogger(CollectTaskConsumer.class);

    private final PulsarClient client;
    private final CatametricasProperties.Tasks settings;
    private final StatsCollector collector;
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
     * @param collector runs the tasks
     * @param objectMapper reads the tasks
     * @param scheduler runs subscription retries
     * @param control pause, resume and status of the consumer
     * @param deadLetters sends invalid messages straight to the DLQ
     * @param sharedSettings source of {@code dlq.max-deliveries}, if available
     * @param commons catacommons configuration, for the default number of deliveries
     */
    public CollectTaskConsumer(PulsarClient client, CatametricasProperties properties, StatsCollector collector,
                               ObjectMapper objectMapper, CatacommonsScheduler scheduler, ConsumerControl control,
                               DeadLetterWriter deadLetters, ObjectProvider<SharedSettings> sharedSettings,
                               CatacommonsProperties commons) {
        this.client = client;
        this.settings = properties.tasks();
        this.collector = collector;
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
        StatsCollector.Outcome outcome = handle(message.getValue());
        switch (outcome) {
            case RETRY -> {
                pauseIfUnavailable();
                source.negativeAcknowledge(message);
            }
            case INVALID -> deadLetters.send(TasksConfig.tasksTopic(), settings.subscription(), message,
                            "not a valid collection task")
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
     * @param payload the message value, a {@link CollectTask} in JSON
     * @return what happened
     */
    StatsCollector.Outcome handle(byte[] payload) {
        CollectTask task;
        try {
            task = objectMapper.readValue(payload, CollectTask.class);
        } catch (IOException e) {
            LOG.error("Collection task is not JSON, sent to the DLQ");
            task = null;
        }
        return collector.handle(task);
    }
}
