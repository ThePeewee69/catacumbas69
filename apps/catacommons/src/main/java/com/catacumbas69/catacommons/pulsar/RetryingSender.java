package com.catacumbas69.catacommons.pulsar;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.TypedMessageBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sends keyed messages to one topic without blocking the caller, retrying with back-off (ADR 0008).
 *
 * <p>The producer is created on first use and again after it failed to be created, so the application
 * starts and keeps working while Pulsar is down. A message that still fails after the last retry is logged
 * at {@code ERROR} with its full content, so it can be recovered from the logs; the caller never sees the
 * failure. Metrics: {@code catacommons.messages{topic,outcome=sent|retried|failed}}.
 *
 * @param <T> message value type
 */
public class RetryingSender<T> implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(RetryingSender.class);

    private final PulsarClient client;
    private final Schema<T> schema;
    private final String topic;
    private final List<Duration> retryDelays;
    private final Duration sendTimeout;
    private final ScheduledExecutorService scheduler;
    private final Function<T, String> describe;
    private final Counter sent;
    private final Counter retried;
    private final Counter failed;
    private CompletableFuture<Producer<T>> producer;
    private boolean closed;

    /**
     * Creates the sender; nothing connects until the first message.
     *
     * @param client Pulsar client
     * @param schema value schema
     * @param topic fully-qualified topic
     * @param retryDelays wait before each retry; its size is the number of retries
     * @param sendTimeout how long one attempt may take
     * @param scheduler runs the retries
     * @param describe renders a value for the log when it is given up (e.g. as JSON)
     * @param meterRegistry metrics registry
     */
    public RetryingSender(PulsarClient client, Schema<T> schema, String topic, List<Duration> retryDelays,
                          Duration sendTimeout, ScheduledExecutorService scheduler, Function<T, String> describe,
                          MeterRegistry meterRegistry) {
        this.client = client;
        this.schema = schema;
        this.topic = topic;
        this.retryDelays = List.copyOf(retryDelays);
        this.sendTimeout = sendTimeout;
        this.scheduler = scheduler;
        this.describe = describe;
        this.sent = counter(meterRegistry, "sent");
        this.retried = counter(meterRegistry, "retried");
        this.failed = counter(meterRegistry, "failed");
    }

    /**
     * Returns the topic messages go to.
     *
     * @return fully-qualified topic
     */
    public String topic() {
        return topic;
    }

    /**
     * Sends a message in the background. Never throws and never blocks.
     *
     * @param key message key, or {@code null}
     * @param value message value; {@code null} sends a tombstone (deletes the key on a compacted topic)
     */
    public void send(String key, T value) {
        attempt(key, value, 0);
    }

    private void attempt(String key, T value, int retries) {
        CompletableFuture<MessageId> result;
        try {
            result = producer().thenCompose(ready -> message(ready, key, value).sendAsync());
        } catch (RuntimeException e) {
            result = CompletableFuture.failedFuture(e);
        }
        result.whenComplete((messageId, error) -> {
            if (error == null) {
                sent.increment();
            } else {
                failedAttempt(key, value, retries, error);
            }
        });
    }

    private TypedMessageBuilder<T> message(Producer<T> ready, String key, T value) {
        TypedMessageBuilder<T> builder = ready.newMessage().value(value);
        if (key != null) {
            builder.key(key);
        }
        return builder;
    }

    private void failedAttempt(String key, T value, int retries, Throwable error) {
        if (retries >= retryDelays.size()) {
            giveUp(key, value, retries + 1, error);
            return;
        }
        Duration delay = retryDelays.get(retries);
        retried.increment();
        LOG.warn("Send to {} failed (attempt {}), retrying in {} ms: {}", topic, retries + 1, delay.toMillis(),
                error.getMessage());
        try {
            scheduler.schedule(() -> attempt(key, value, retries + 1), delay.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            giveUp(key, value, retries + 1, error);
        }
    }

    private void giveUp(String key, T value, int attempts, Throwable error) {
        failed.increment();
        LOG.error("Message NOT sent to {} after {} attempt(s): key={} value={}", topic, attempts, key,
                value == null ? "<tombstone>" : describe.apply(value), error);
    }

    private synchronized CompletableFuture<Producer<T>> producer() {
        if (closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("sender closed"));
        }
        if (producer == null || producer.isCompletedExceptionally()) {
            producer = client.newProducer(schema)
                    .topic(topic)
                    .sendTimeout((int) Math.min(Integer.MAX_VALUE, sendTimeout.toMillis()), TimeUnit.MILLISECONDS)
                    .blockIfQueueFull(false)
                    .createAsync();
        }
        return producer;
    }

    /** Waits for messages in flight and closes the producer. Retries not yet started are given up. */
    @Override
    public void close() {
        CompletableFuture<Producer<T>> current;
        synchronized (this) {
            closed = true;
            current = producer;
        }
        if (current == null || !current.isDone() || current.isCompletedExceptionally()) {
            return;
        }
        try {
            current.join().close();
        } catch (PulsarClientException | RuntimeException e) {
            LOG.warn("Producer for {} not closed cleanly: {}", topic, e.getMessage());
        }
    }

    private Counter counter(MeterRegistry registry, String outcome) {
        return Counter.builder("catacommons.messages")
                .description("Messages sent by catacommons, by outcome")
                .tag("topic", topic)
                .tag("outcome", outcome)
                .register(registry);
    }
}
