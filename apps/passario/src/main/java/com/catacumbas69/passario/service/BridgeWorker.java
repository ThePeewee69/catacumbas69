package com.catacumbas69.passario.service;

import com.catacumbas69.passario.document.BridgeDocument;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * A single running bridge: consumes from a source topic and fans each message out
 * to one or more destination topics. Runs in a dedicated daemon thread; stopped via {@link #stop()}.
 *
 * <p>Delivery is at-least-once: the source message is acknowledged only after every
 * destination has accepted it. If any destination fails, the message is nack-ed and
 * redelivered, so destinations that already received it may see a duplicate.
 */
@Slf4j
public class BridgeWorker implements Runnable {

    private static final long SEND_TIMEOUT_SECONDS = 30;

    private final BridgeDocument config;
    private final PulsarClient pulsarClient;
    private final List<String> destinationTopics;

    private Consumer<byte[]> consumer;
    private final List<Producer<byte[]>> producers = new ArrayList<>();
    private Thread thread;
    private volatile boolean running = false;

    private volatile long messagesForwarded = 0;
    private volatile long errors = 0;
    private Instant startedAt;

    public BridgeWorker(BridgeDocument config, PulsarClient pulsarClient) {
        this.config = config;
        this.pulsarClient = pulsarClient;
        this.destinationTopics = config.resolveDestinationTopics();
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    public void start() throws PulsarClientException {
        if (destinationTopics.isEmpty()) {
            throw new PulsarClientException("Bridge '" + config.getName() + "' has no destination topics");
        }

        try {
            for (String topic : destinationTopics) {
                producers.add(pulsarClient.newProducer().topic(topic).create());
            }

            SubscriptionType subType = resolveSubscriptionType(config.getSource().getSubscriptionType());
            consumer = pulsarClient.newConsumer()
                    .topic(config.getSource().getTopic())
                    .subscriptionName(config.getSource().getSubscription())
                    .subscriptionType(subType)
                    .subscribe();
        } catch (PulsarClientException e) {
            closeResources();   // don't leak producers created before the failure
            throw e;
        }

        running = true;
        startedAt = Instant.now();
        thread = new Thread(this, "bridge-" + config.getName());
        thread.setDaemon(true);
        thread.start();

        log.info("[Passário] Bridge '{}' started: {} → {}",
                config.getName(), config.getSource().getTopic(), destinationTopics);
    }

    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            try { thread.join(5_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    // ── Message loop ─────────────────────────────────────────────────────────

    @Override
    public void run() {
        while (running) {
            Message<byte[]> msg;
            try {
                msg = consumer.receive(1, TimeUnit.SECONDS);
            } catch (PulsarClientException e) {
                if (running) {
                    log.error("[Passário] Bridge '{}' receive error: {}", config.getName(), e.getMessage());
                    errors++;
                    try { Thread.sleep(1_000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
                continue;
            }
            if (msg == null) continue;

            try {
                List<CompletableFuture<MessageId>> sends = new ArrayList<>(producers.size());
                for (Producer<byte[]> producer : producers) {
                    sends.add(buildMessage(producer, msg).sendAsync());
                }
                CompletableFuture.allOf(sends.toArray(CompletableFuture[]::new))
                        .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);

                consumer.acknowledge(msg);
                messagesForwarded++;

            } catch (InterruptedException e) {
                consumer.negativeAcknowledge(msg);
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.warn("[Passário] Bridge '{}' forward failed, nack-ing: {}", config.getName(), e.getMessage());
                consumer.negativeAcknowledge(msg);
                errors++;
            }
        }

        closeResources();
        log.info("[Passário] Bridge '{}' stopped. Forwarded: {}, Errors: {}", config.getName(), messagesForwarded, errors);
    }

    private TypedMessageBuilder<byte[]> buildMessage(Producer<byte[]> producer, Message<byte[]> msg) {
        TypedMessageBuilder<byte[]> builder = producer.newMessage().value(msg.getData());
        if (config.isPreserveKey() && msg.hasKey()) {
            builder.key(msg.getKey());
        }
        if (config.isPreserveProperties() && !msg.getProperties().isEmpty()) {
            builder.properties(msg.getProperties());
        }
        if (msg.getEventTime() > 0) {
            builder.eventTime(msg.getEventTime());
        }
        return builder;
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public String getBridgeId() { return config.getId(); }

    public Instant getConfigUpdatedAt() { return config.getUpdatedAt(); }

    public BridgeWorkerStatus getStatus() {
        return new BridgeWorkerStatus(
                config.getId(),
                config.getName(),
                running,
                messagesForwarded,
                errors,
                startedAt,
                config.getSource().getTopic(),
                destinationTopics
        );
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private SubscriptionType resolveSubscriptionType(String raw) {
        try { return SubscriptionType.valueOf(raw); }
        catch (Exception e) {
            log.warn("[Passário] Unknown subscription type '{}', defaulting to Shared", raw);
            return SubscriptionType.Shared;
        }
    }

    private void closeResources() {
        try { if (consumer != null) consumer.close(); } catch (Exception ignored) {}
        for (Producer<byte[]> p : producers) {
            try { p.close(); } catch (Exception ignored) {}
        }
        producers.clear();
    }
}
