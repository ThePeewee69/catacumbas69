package com.catacumbas69.passario.service;

import com.catacumbas69.passario.config.PassarioProperties;
import com.catacumbas69.passario.model.BridgeDocument;
import com.catacumbas69.passario.model.TrafficRole;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.SubscriptionType;
import org.apache.pulsar.client.api.TypedMessageBuilder;
import org.slf4j.MDC;

/**
 * Relays one bridge: consumes the source topic and sends every message to all destination topics.
 *
 * <h2>Performance (PERF-2, PERF-3)</h2>
 * Sends are asynchronous and pipelined; up to {@code maxInFlight} messages may be unconfirmed at once,
 * bounded by a semaphore. Producers batch and consumers prefetch and group acknowledgements.
 *
 * <h2>Delivery (ARCH-6, PERF-4)</h2>
 * A source message is acknowledged only after every destination accepted it (at-least-once).
 * <ul>
 *   <li><b>Unordered</b>: a failed message is negatively acknowledged and redelivered later.</li>
 *   <li><b>Ordered</b>: on any failure the worker stops acknowledging, waits for every in-flight send to
 *   settle, then asks the broker to redeliver all unacknowledged messages, so the stream is replayed in
 *   source order. Duplicates are possible; a message sent in the instant between a producer failure and
 *   the pause may still overtake the replay.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * The receive loop runs on its own virtual thread. Send completions run on Pulsar I/O threads and only
 * acknowledge, record metrics and release permits. {@link #start()} and {@link #stop(Duration)} are
 * called by {@link BridgeManager}; a worker is started at most once.
 */
@Slf4j
public class BridgeWorker {

    private static final int RECEIVE_POLL_MILLIS = 200;

    private final BridgeDocument config;
    private final PulsarClient client;
    private final PassarioProperties.Bridge settings;
    private final BridgeMetrics metrics;
    private final TopicTraffic sourceTraffic;
    private final List<TopicTraffic> destinationTraffic;
    private final List<String> destinationTopics;
    private final boolean ordered;
    private final int maxInFlight;
    private final Semaphore inFlight;
    private final List<Producer<byte[]>> producers = new ArrayList<>();
    private final AtomicReference<String> lastError = new AtomicReference<>();
    private final Object lifecycleLock = new Object();

    private volatile Consumer<byte[]> consumer;
    private volatile Thread loopThread;
    private volatile BridgeState state = BridgeState.STARTING;
    private volatile boolean replayRequested;
    private volatile Instant startedAt;

    /**
     * Creates a worker for a bridge definition. Nothing is opened until {@link #start()}.
     *
     * @param config bridge definition snapshot; later changes to the document do not affect the worker
     * @param client shared Pulsar client
     * @param settings runtime tuning
     * @param metrics meters for this bridge
     * @param traffic per-topic message counts stored in MongoDB
     */
    public BridgeWorker(BridgeDocument config, PulsarClient client, PassarioProperties.Bridge settings,
                        BridgeMetrics metrics, TrafficRecorder traffic) {
        this.config = config;
        this.client = client;
        this.settings = settings;
        this.metrics = metrics;
        this.destinationTopics = config.resolveDestinationTopics();
        this.ordered = config.isOrderedEffective();
        this.maxInFlight = config.getMaxInFlight() != null ? config.getMaxInFlight() : settings.defaultMaxInFlight();
        this.inFlight = new Semaphore(maxInFlight);
        metrics.registerInFlight(this::inFlightCount);
        this.sourceTraffic = traffic.series(config.getId(), config.getName(), TrafficRole.SOURCE,
                config.getSource().getTopic());
        this.destinationTraffic = destinationTopics.stream()
                .map(topic -> traffic.series(config.getId(), config.getName(), TrafficRole.DESTINATION, topic))
                .toList();
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    /**
     * Creates the producers and the consumer without blocking the caller, then starts the receive loop.
     * If anything fails, every resource created so far is closed and the returned future fails.
     *
     * @return a future completed when the worker is relaying, or completed exceptionally on failure
     */
    public CompletableFuture<Void> start() {
        if (destinationTopics.isEmpty()) {
            return fail(new IllegalStateException("Bridge has no destination topics"));
        }
        List<CompletableFuture<Producer<byte[]>>> pendingProducers;
        try {
            pendingProducers = destinationTopics.stream().map(this::createProducer).toList();
        } catch (RuntimeException e) {
            return fail(e);
        }
        return CompletableFuture.allOf(pendingProducers.toArray(CompletableFuture[]::new))
                .thenCompose(ignored -> {
                    pendingProducers.forEach(future -> producers.add(future.join()));
                    return subscribe();
                })
                .thenAccept(this::startLoop)
                .whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        markFailedUnlessStopping();
                        lastError.set(rootMessage(failure));
                        pendingProducers.forEach(future -> future.thenAccept(Producer::closeAsync));
                    }
                });
    }

    /**
     * Stops the worker: ends the receive loop, waits for in-flight sends (bounded by {@code timeout}),
     * flushes and closes producers, closes the consumer and unregisters the meters. Messages that were
     * not acknowledged are redelivered by the broker later (RES-7).
     *
     * @param timeout maximum time to wait for each phase
     */
    public void stop(Duration timeout) {
        Thread thread;
        synchronized (lifecycleLock) {
            if (state == BridgeState.STOPPED || state == BridgeState.STOPPING) {
                return;
            }
            state = BridgeState.STOPPING;
            thread = loopThread;
        }
        if (thread != null) {
            thread.interrupt();
            joinQuietly(thread, timeout);
        }
        drainInFlight(timeout);
        producers.forEach(producer -> awaitQuietly(producer.flushAsync()
                .thenCompose(ignored -> producer.closeAsync()), timeout));
        Consumer<byte[]> current = consumer;
        if (current != null) {
            awaitQuietly(current.closeAsync(), timeout);
        }
        metrics.remove();
        state = BridgeState.STOPPED;
        log.info("Bridge '{}' stopped (forwarded={}, errors={})",
                config.getName(), metrics.forwardedCount(), metrics.errorCount());
    }

    // ── Receive loop ─────────────────────────────────────────────────────────

    private void startLoop(Consumer<byte[]> subscribed) {
        synchronized (lifecycleLock) {
            if (state != BridgeState.STARTING) {
                // stop() was called while starting: release what was just created.
                subscribed.closeAsync();
                producers.forEach(Producer::closeAsync);
                return;
            }
            consumer = subscribed;
            startedAt = Instant.now();
            state = BridgeState.RUNNING;
            loopThread = Thread.ofVirtual().name("bridge-" + config.getName()).start(this::runLoop);
        }
        log.info("Bridge '{}' started: {} → {} ({}, maxInFlight={})", config.getName(),
                config.getSource().getTopic(), destinationTopics, ordered ? "ordered" : "unordered", maxInFlight);
    }

    private void runLoop() {
        MDC.put("bridgeId", config.getId());
        MDC.put("bridgeName", config.getName());
        try {
            while (state == BridgeState.RUNNING) {
                if (replayRequested) {
                    replay();
                    continue;
                }
                if (!inFlight.tryAcquire(RECEIVE_POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                    continue;
                }
                Message<byte[]> message = receive();
                if (message == null) {
                    inFlight.release();
                    continue;
                }
                forward(message);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            MDC.clear();
        }
    }

    private Message<byte[]> receive() throws InterruptedException {
        try {
            return consumer.receive(RECEIVE_POLL_MILLIS, TimeUnit.MILLISECONDS);
        } catch (PulsarClientException e) {
            if (state != BridgeState.RUNNING || Thread.currentThread().isInterrupted()) {
                return null;
            }
            metrics.error();
            lastError.set(e.getMessage());
            log.warn("Bridge '{}' receive failed: {}", config.getName(), e.getMessage());
            Thread.sleep(settings.negativeAckRedeliveryDelay().toMillis());
            return null;
        }
    }

    /**
     * Sends the message to every destination. Exactly one permit was acquired for it; it is released
     * exactly once, in {@link #onForwardComplete}.
     */
    private void forward(Message<byte[]> message) {
        long size = payloadSize(message);
        CompletableFuture<Void> allSent;
        try {
            List<CompletableFuture<MessageId>> sends = new ArrayList<>(producers.size());
            for (int i = 0; i < producers.size(); i++) {
                TopicTraffic delivered = destinationTraffic.get(i);
                CompletableFuture<MessageId> send = buildMessage(producers.get(i), message).sendAsync();
                send.thenRun(() -> delivered.add(size));
                if (ordered) {
                    // Pause as soon as any destination fails, not when the slowest one answers.
                    send.exceptionally(failure -> {
                        replayRequested = true;
                        return null;
                    });
                }
                sends.add(send);
            }
            allSent = CompletableFuture.allOf(sends.toArray(CompletableFuture[]::new));
        } catch (RuntimeException e) {
            allSent = CompletableFuture.failedFuture(e);
        }
        allSent.whenComplete((ignored, failure) -> onForwardComplete(message, size, failure));
    }

    private void onForwardComplete(Message<byte[]> message, long size, Throwable failure) {
        try {
            if (failure != null) {
                onForwardFailure(message, failure);
            } else if (ordered && replayRequested) {
                // Leave it unacknowledged: it is replayed after the failed message, keeping order.
                return;
            } else {
                consumer.acknowledgeAsync(message.getMessageId())
                        .exceptionally(ackFailure -> {
                            log.debug("Bridge '{}' ack failed: {}", config.getName(), ackFailure.getMessage());
                            return null;
                        });
                metrics.forwarded();
                sourceTraffic.add(size);
            }
        } finally {
            inFlight.release();
        }
    }

    private void onForwardFailure(Message<byte[]> message, Throwable failure) {
        metrics.error();
        lastError.set(rootMessage(failure));
        log.warn("Bridge '{}' failed to forward {}: {}", config.getName(), message.getMessageId(),
                rootMessage(failure));
        if (ordered) {
            replayRequested = true;
        } else {
            consumer.negativeAcknowledge(message);
        }
    }

    /**
     * Ordered mode recovery: wait until nothing is in flight, then redeliver everything that was not
     * acknowledged, in order. The delay avoids a hot loop while a destination is down (RES-8).
     */
    private void replay() throws InterruptedException {
        inFlight.acquire(maxInFlight);
        try {
            consumer.redeliverUnacknowledgedMessages();
            replayRequested = false;
            log.info("Bridge '{}' replaying unacknowledged messages in order", config.getName());
        } finally {
            inFlight.release(maxInFlight);
        }
        Thread.sleep(settings.negativeAckRedeliveryDelay().toMillis());
    }

    private static long payloadSize(Message<byte[]> message) {
        byte[] data = message.getData();
        return data == null ? 0 : data.length;
    }

    private TypedMessageBuilder<byte[]> buildMessage(Producer<byte[]> producer, Message<byte[]> message) {
        TypedMessageBuilder<byte[]> builder = producer.newMessage().value(message.getData());
        if (config.isPreserveKey() && message.hasKey()) {
            builder.key(message.getKey());
        }
        if (config.isPreserveProperties() && !message.getProperties().isEmpty()) {
            builder.properties(message.getProperties());
        }
        if (message.getEventTime() > 0) {
            builder.eventTime(message.getEventTime());
        }
        return builder;
    }

    // ── Pulsar resources ─────────────────────────────────────────────────────

    private CompletableFuture<Producer<byte[]>> createProducer(String topic) {
        return client.newProducer()
                .topic(topic)
                .enableBatching(true)
                .batchingMaxPublishDelay(settings.batchingMaxPublishDelay().toMillis(), TimeUnit.MILLISECONDS)
                .batchingMaxMessages(settings.batchingMaxMessages())
                .blockIfQueueFull(true)
                .sendTimeout((int) settings.sendTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .createAsync();
    }

    private CompletableFuture<Consumer<byte[]>> subscribe() {
        return client.newConsumer()
                .topic(config.getSource().getTopic())
                .subscriptionName(config.getSource().getSubscription())
                .subscriptionType(subscriptionType())
                .receiverQueueSize(settings.receiverQueueSize())
                .acknowledgmentGroupTime(settings.acknowledgmentGroupTime().toMillis(), TimeUnit.MILLISECONDS)
                .negativeAckRedeliveryDelay(settings.negativeAckRedeliveryDelay().toMillis(), TimeUnit.MILLISECONDS)
                .subscribeAsync();
    }

    private SubscriptionType subscriptionType() {
        String type = config.getSource().subscriptionTypeOrDefault();
        try {
            return SubscriptionType.valueOf(type);
        } catch (IllegalArgumentException e) {
            log.warn("Bridge '{}' has unknown subscription type '{}', using Shared", config.getName(), type);
            return SubscriptionType.Shared;
        }
    }

    // ── Status ───────────────────────────────────────────────────────────────

    /**
     * Returns the bridge id.
     *
     * @return bridge id
     */
    public String getBridgeId() {
        return config.getId();
    }

    /**
     * Returns the document version this worker was created from; a different stored version means the
     * definition changed and the worker must be restarted.
     *
     * @return configuration version, may be {@code null} for documents not yet migrated
     */
    public Long getConfigVersion() {
        return config.getVersion();
    }

    /**
     * Returns the lifecycle state.
     *
     * @return current state
     */
    public BridgeState getState() {
        return state;
    }

    /**
     * Returns a snapshot of the runtime status.
     *
     * @return status snapshot
     */
    public BridgeWorkerStatus getStatus() {
        return new BridgeWorkerStatus(
                config.getId(),
                config.getName(),
                state,
                state == BridgeState.RUNNING,
                metrics.forwardedCount(),
                metrics.errorCount(),
                inFlightCount(),
                startedAt,
                config.getSource().getTopic(),
                destinationTopics,
                ordered,
                lastError.get(),
                null);
    }

    private int inFlightCount() {
        return maxInFlight - inFlight.availablePermits();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void markFailedUnlessStopping() {
        synchronized (lifecycleLock) {
            if (state == BridgeState.STARTING) {
                state = BridgeState.FAILED;
            }
        }
    }

    private CompletableFuture<Void> fail(Throwable failure) {
        markFailedUnlessStopping();
        lastError.set(rootMessage(failure));
        return CompletableFuture.failedFuture(failure);
    }

    private void drainInFlight(Duration timeout) {
        try {
            if (inFlight.tryAcquire(maxInFlight, timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                inFlight.release(maxInFlight);
            } else {
                log.warn("Bridge '{}' stopped with {} message(s) still in flight; they will be redelivered",
                        config.getName(), inFlightCount());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void joinQuietly(Thread thread, Duration timeout) {
        try {
            thread.join(timeout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void awaitQuietly(CompletableFuture<?> future, Duration timeout) {
        try {
            future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            log.debug("Bridge '{}' resource did not close cleanly: {}", config.getName(), rootMessage(e));
        }
    }

    /**
     * Unwraps completion wrappers to the message that explains the failure.
     *
     * @param failure any throwable
     * @return the most specific message available
     */
    static String rootMessage(Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
    }
}
