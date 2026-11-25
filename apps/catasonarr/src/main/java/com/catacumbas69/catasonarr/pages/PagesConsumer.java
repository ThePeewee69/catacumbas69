package com.catacumbas69.catasonarr.pages;

import com.catacumbas69.catacommons.CatacommonsScheduler;
import com.catacumbas69.catacommons.consumers.ConsumerControl;
import com.catacumbas69.catacommons.consumers.ConsumerGate;
import com.catacumbas69.catacommons.events.CloudEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.SubscriptionInitialPosition;
import org.apache.pulsar.client.api.SubscriptionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;

/**
 * Reads {@code catacumbas69/catasonarr/pages} for this replica's open pages (orch.md decision 118), with its own
 * durable subscription {@code catasonarr-pages-<pod>} ({@code Exclusive}, from the newest message), so every
 * replica tells its pages everything. The topic keeps a message for an hour at most (message TTL), so a
 * subscription left behind by an old pod holds no more than an hour of changes. A change older than a minute
 * (read after a restart) is acknowledged without telling anyone: the pages just loaded and have it. Every message is
 * acknowledged: a page that misses one reloads on the next. Paused, resumed and checked from Catapulsar's Consumers
 * panel like the other consumers; no DLQ, as there is nothing to retry.
 */
public class PagesConsumer implements ApplicationListener<ApplicationReadyEvent>, DisposableBean {

    /** A change older than this when read is not sent to the pages. */
    static final Duration TOO_OLD = Duration.ofMinutes(1);

    private static final Logger LOG = LoggerFactory.getLogger(PagesConsumer.class);
    private static final long RETRY_SECONDS = 10;
    private static final long PING_SECONDS = 25;

    private final PulsarClient client;
    private final PulsarAdmin admin;
    private final ObjectMapper objectMapper;
    private final CatacommonsScheduler scheduler;
    private final ConsumerControl control;
    private final PageUpdates pages;
    private final Clock clock;
    private final String topic;
    private final String subscription;
    private final ConsumerGate gate;
    private volatile Consumer<byte[]> consumer;
    private volatile boolean ttlSet;
    private volatile boolean stopping;

    /**
     * Creates the consumer; nothing connects until the application is ready.
     *
     * @param client Pulsar client
     * @param admin Pulsar admin client, for the topic's message TTL
     * @param objectMapper reads the events
     * @param scheduler runs the retries and the pings
     * @param control pause, resume and status of the consumer
     * @param pages the open pages
     * @param app this app's name
     * @param clock time source
     */
    public PagesConsumer(PulsarClient client, PulsarAdmin admin, ObjectMapper objectMapper,
                         CatacommonsScheduler scheduler, ConsumerControl control, PageUpdates pages, String app,
                         Clock clock) {
        this.client = client;
        this.admin = admin;
        this.objectMapper = objectMapper;
        this.scheduler = scheduler;
        this.control = control;
        this.pages = pages;
        this.clock = clock;
        this.topic = PagesTopics.topic(app);
        this.subscription = PagesTopics.subscription(app, control.instance());
        this.gate = control.register(PagesTopics.CONSUMER, topic, subscription, Set.of());
    }

    /**
     * Subscribes in the background, and pings the pages now and then.
     *
     * @param event the application is ready
     */
    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        try {
            scheduler.executor().scheduleWithFixedDelay(pages::ping, PING_SECONDS, PING_SECONDS, TimeUnit.SECONDS);
        } catch (RejectedExecutionException e) {
            LOG.debug("Pages not pinged, shutting down");
        }
        subscribe();
    }

    /**
     * Returns the consumer's pause state.
     *
     * @return the gate
     */
    public ConsumerGate gate() {
        return gate;
    }

    /** Closes the consumer on shutdown; the subscription stays, and its messages expire with the topic's TTL. */
    @Override
    public void destroy() {
        stopping = true;
        Consumer<byte[]> current = consumer;
        if (current != null) {
            current.closeAsync();
        }
    }

    /**
     * Turns one message into a change for the pages.
     *
     * @param payload the event, as the bridge copied it
     * @return the change as JSON, or empty when it is not one, or too old
     */
    Optional<String> handle(byte[] payload) {
        CloudEvent event;
        try {
            event = objectMapper.readValue(payload, CloudEvent.class);
        } catch (IOException e) {
            LOG.warn("Not an event on {}, ignored: {}", topic, e.getMessage());
            return Optional.empty();
        }
        Optional<PageChange> change = PageChange.of(event).filter(c -> !tooOld(c.time()));
        try {
            return change.isEmpty() ? Optional.empty() : Optional.of(objectMapper.writeValueAsString(change.get()));
        } catch (IOException e) {
            LOG.warn("Change not written for the pages: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private boolean tooOld(String time) {
        try {
            return time == null || Instant.parse(time).isBefore(clock.instant().minus(TOO_OLD));
        } catch (DateTimeParseException e) {
            return true;
        }
    }

    private void subscribe() {
        if (stopping) {
            return;
        }
        client.newConsumer(Schema.BYTES)
                .topic(topic)
                .subscriptionName(subscription)
                .consumerName(control.consumerName())
                .subscriptionType(SubscriptionType.Exclusive)
                .subscriptionInitialPosition(SubscriptionInitialPosition.Latest)
                .messageListener(this::onMessage)
                .subscribeAsync()
                .whenComplete((subscribed, error) -> {
                    if (error == null) {
                        consumer = subscribed;
                        gate.attach(subscribed, null, 0);
                        LOG.info("Telling the pages what arrives on {} as {}", topic, subscription);
                        setTtl();
                    } else {
                        LOG.warn("Pages topic not subscribed, retrying in {} s: {}", RETRY_SECONDS,
                                error.getMessage());
                        later(this::subscribe);
                    }
                });
    }

    /** The topic exists once subscribed: its message TTL is set then, and again until Pulsar accepts it. */
    private void setTtl() {
        if (ttlSet || stopping) {
            return;
        }
        try {
            admin.topicPolicies().setMessageTTL(topic, PagesTopics.MESSAGE_TTL_SECONDS);
            ttlSet = true;
        } catch (PulsarAdminException | RuntimeException e) {
            LOG.warn("Message TTL of {} not set, retrying in {} s: {}", topic, RETRY_SECONDS, e.getMessage());
            later(this::setTtl);
        }
    }

    private void later(Runnable task) {
        try {
            scheduler.executor().schedule(task, RETRY_SECONDS, TimeUnit.SECONDS);
        } catch (RejectedExecutionException e) {
            LOG.debug("Not retried, shutting down");
        }
    }

    private void onMessage(Consumer<byte[]> source, Message<byte[]> message) {
        if (gate.isPaused()) {
            source.negativeAcknowledge(message); // received before the pause took effect; it comes back after it
            return;
        }
        handle(message.getValue()).ifPresent(pages::broadcast);
        source.acknowledgeAsync(message);
    }
}
