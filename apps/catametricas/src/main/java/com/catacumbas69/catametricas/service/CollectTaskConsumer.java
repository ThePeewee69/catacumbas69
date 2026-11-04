package com.catacumbas69.catametricas.service;

import com.catacumbas69.catacommons.CatacommonsScheduler;
import com.catacumbas69.catametricas.config.CatametricasProperties;
import com.catacumbas69.catametricas.config.TasksConfig;
import com.catacumbas69.catametricas.model.CollectTask;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.SubscriptionType;
import org.apache.pulsar.client.impl.MultiplierRedeliveryBackoff;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code catacumbas69/catametricas/collect-tasks} and runs each task with {@link StatsCollector}
 * (ADR 0012).
 *
 * <p>Every instance joins the same {@code Shared} subscription with a small receiver queue, so the tasks spread
 * over the instances. A task is acknowledged once done, skipped (already claimed) or dropped (not valid); it is
 * negatively acknowledged when Pulsar or MongoDB failed and delivered again after a wait that doubles from
 * {@code catametricas.tasks.retry-delay} up to {@code max-retry-delay}. Subscribing happens in the background
 * and is retried, so Catametricas starts while Pulsar is down.
 */
@Component
public class CollectTaskConsumer implements ApplicationListener<ApplicationReadyEvent>, DisposableBean {

    private static final Logger LOG = LoggerFactory.getLogger(CollectTaskConsumer.class);

    private final PulsarClient client;
    private final CatametricasProperties.Tasks settings;
    private final StatsCollector collector;
    private final ObjectMapper objectMapper;
    private final CatacommonsScheduler scheduler;
    private final String consumerName;
    private volatile Consumer<byte[]> consumer;
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
     * @param state this instance, named in its consumer ({@code catametricas@<instance>})
     */
    public CollectTaskConsumer(PulsarClient client, CatametricasProperties properties, StatsCollector collector,
                               ObjectMapper objectMapper, CatacommonsScheduler scheduler, CollectorState state) {
        this.consumerName = "catametricas@" + state.instance();
        this.client = client;
        this.settings = properties.tasks();
        this.collector = collector;
        this.objectMapper = objectMapper;
        this.scheduler = scheduler;
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

    /** Closes the consumer on shutdown; unacknowledged tasks are delivered to another instance. */
    @Override
    public void destroy() {
        stopping = true;
        Consumer<byte[]> current = consumer;
        if (current != null) {
            try {
                current.close();
            } catch (PulsarClientException e) {
                LOG.warn("Task consumer not closed cleanly: {}", e.getMessage());
            }
        }
    }

    private void subscribe() {
        if (stopping) {
            return;
        }
        client.newConsumer(Schema.BYTES)
                .topic(TasksConfig.tasksTopic())
                .subscriptionName(settings.subscription())
                .consumerName(consumerName)
                .subscriptionType(SubscriptionType.Shared)
                .receiverQueueSize(settings.receiverQueueSize())
                .negativeAckRedeliveryBackoff(MultiplierRedeliveryBackoff.builder()
                        .minDelayMs(settings.retryDelay().toMillis())
                        .maxDelayMs(settings.maxRetryDelay().toMillis())
                        .multiplier(2)
                        .build())
                .messageListener(this::onMessage)
                .subscribeAsync()
                .whenComplete((subscribed, error) -> {
                    if (error == null) {
                        consumer = subscribed;
                        lastError = null;
                        LOG.info("Consuming {} as {}", TasksConfig.tasksTopic(), settings.subscription());
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
        if (handle(message.getValue()) == StatsCollector.Outcome.RETRY) {
            source.negativeAcknowledge(message);
        } else {
            source.acknowledgeAsync(message);
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
            LOG.error("Collection task is not JSON, dropped: {}", new String(payload, StandardCharsets.UTF_8));
            task = null;
        }
        return collector.handle(task);
    }
}
