package com.catacumbas69.catacommons.consumers;

import com.catacumbas69.catacommons.CatacommonsProperties;
import com.catacumbas69.catacommons.events.EventData;
import com.catacumbas69.catacommons.events.EventPublisher;
import com.catacumbas69.catacommons.pulsar.CatacumbasTopics;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.Reader;
import org.apache.pulsar.client.api.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;

/**
 * The app's consumers under control (ADR 0015, orch.md decisions 66, 67).
 *
 * <ul>
 *   <li>Each consumer is a {@link ConsumerGate}, registered by the app with the dependencies it needs.</li>
 *   <li>Every {@code status-interval} and on every change, each gate's {@link ConsumerStatus} is published on the
 *       compacted topic {@code catacumbas69/<app>/consumers}, key {@code <instance>/<consumer>}; a tombstone is
 *       written on shutdown.</li>
 *   <li>Every {@code resume-check-interval}, the gates paused automatically are resumed once their dependencies
 *       answer.</li>
 *   <li>{@link ConsumerCommand}s ({@code pause}, {@code resume}, {@code check}) are read from
 *       {@code catacumbas69/<app>/consumer-commands}, from the latest message, by every replica.</li>
 *   <li>Changes and checks are published as {@code consumer.paused|resumed|checked} events, so Cataudit has them.</li>
 * </ul>
 *
 * <p>Status and commands go through Pulsar, not MongoDB: the usual reason to pause is MongoDB being down.
 * Probes run on virtual threads, never on the catacommons thread.
 */
public class ConsumerControl implements ApplicationListener<ApplicationReadyEvent>, AutoCloseable {

    /** Entity of the events this class publishes. */
    public static final String ENTITY = "consumer";

    private static final Logger LOG = LoggerFactory.getLogger(ConsumerControl.class);

    private final PulsarClient client;
    private final String app;
    private final InstanceId instance;
    private final List<ConnectionProbe> probes;
    private final EventPublisher events;
    private final ScheduledExecutorService scheduler;
    private final CatacommonsProperties.Consumers settings;
    private final Clock clock;
    private final List<ConsumerGate> gates = new CopyOnWriteArrayList<>();

    private volatile Producer<ConsumerStatus> statusProducer;
    private volatile Reader<ConsumerCommand> commandReader;
    private volatile ConnectionCheck lastCheck;
    private volatile boolean closed;

    /**
     * Creates the control.
     *
     * @param client Pulsar client
     * @param app app name
     * @param instance this replica
     * @param probes the app's connection checks
     * @param events publishes the changes
     * @param scheduler runs the periodic work
     * @param settings intervals and defaults
     * @param clock time source
     */
    public ConsumerControl(PulsarClient client, String app, InstanceId instance, List<ConnectionProbe> probes,
                           EventPublisher events, ScheduledExecutorService scheduler,
                           CatacommonsProperties.Consumers settings, Clock clock) {
        this.client = client;
        this.app = app;
        this.instance = instance;
        this.probes = List.copyOf(probes);
        this.events = events;
        this.scheduler = scheduler;
        this.settings = settings;
        this.clock = clock;
    }

    /**
     * Registers a consumer.
     *
     * @param name consumer name inside the app, e.g. {@code audit}
     * @param topic topic it reads
     * @param subscription its subscription
     * @param dependencies probes that must answer before an automatic resume, e.g. {@code mongodb}
     * @return its gate
     */
    public ConsumerGate register(String name, String topic, String subscription, Set<String> dependencies) {
        ConsumerGate gate = new ConsumerGate(name, topic, subscription, dependencies, app, clock, this::changed);
        gates.add(gate);
        return gate;
    }

    /**
     * Returns the Pulsar consumer name to use for a consumer of this replica.
     *
     * @return {@code <app>@<instance>}
     */
    public String consumerName() {
        return app + "@" + instance.id();
    }

    /**
     * Returns this replica's identity.
     *
     * @return the instance id
     */
    public String instance() {
        return instance.id();
    }

    /**
     * Returns the registered consumers.
     *
     * @return the gates
     */
    public List<ConsumerGate> gates() {
        return List.copyOf(gates);
    }

    /**
     * Returns the last connection check.
     *
     * @return the check, or {@code null}
     */
    public ConnectionCheck lastCheck() {
        return lastCheck;
    }

    /**
     * Opens the status producer and the command reader, and starts the periodic work.
     *
     * @param event the application is ready
     */
    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        openStatusProducer();
        openCommandReader();
        every(settings.statusInterval().toMillis(), this::publishAll);
        every(settings.resumeCheckInterval().toMillis(),
                () -> Thread.ofVirtual().start(this::resumeWhereDependenciesAnswer));
    }

    /**
     * Runs every probe now, keeps the result and publishes it ({@code consumer.checked}).
     *
     * @param actor who asked
     * @return the check
     */
    public ConnectionCheck check(String actor) {
        Map<String, ProbeResult> results = new LinkedHashMap<>();
        for (ConnectionProbe probe : probes) {
            results.put(probe.name(), safeProbe(probe));
        }
        ConnectionCheck check = new ConnectionCheck(clock.instant().toString(), actor, results);
        lastCheck = check;
        Map<String, Object> details = new LinkedHashMap<>();
        results.forEach((name, result) -> {
            details.put(name + ".up", result.up());
            details.put(name + ".millis", result.millis());
            details.put(name + ".error", result.error());
        });
        publishEvent("checked", instance.id(), actor, details);
        publishAll();
        return check;
    }

    /**
     * Carries out a command if it is meant for this replica.
     *
     * @param command the command
     */
    public void handle(ConsumerCommand command) {
        if (command == null || command.action() == null || command.instance() == null
                || !command.forInstance(instance.id())) {
            return;
        }
        String actor = command.actor() == null ? "unknown" : command.actor();
        switch (command.action()) {
            case ConsumerCommand.PAUSE -> gates.stream().filter(g -> command.forConsumer(g.name()))
                    .forEach(g -> g.pauseManually(actor));
            case ConsumerCommand.RESUME -> gates.stream().filter(g -> command.forConsumer(g.name()))
                    .forEach(g -> g.resumeManually(actor));
            case ConsumerCommand.CHECK -> Thread.ofVirtual().start(() -> check(actor));
            default -> LOG.warn("Unknown consumer command '{}' ignored", command.action());
        }
    }

    /** Resumes the gates paused automatically whose dependencies answer now. */
    void resumeWhereDependenciesAnswer() {
        for (ConsumerGate gate : gates) {
            if (ConsumerGate.PAUSED_AUTO.equals(gate.state()) && dependenciesAnswer(gate)) {
                LOG.info("Consumer {} resumed: {} answer again", gate.name(), gate.dependencies());
                gate.resumeAutomatically();
            }
        }
    }

    private boolean dependenciesAnswer(ConsumerGate gate) {
        return probes.stream().filter(probe -> gate.dependencies().contains(probe.name()))
                .allMatch(probe -> safeProbe(probe).up());
    }

    private static ProbeResult safeProbe(ConnectionProbe probe) {
        try {
            return probe.probe();
        } catch (RuntimeException e) {
            return ProbeResult.down(0, e.getMessage());
        }
    }

    private void changed(ConsumerGate gate, String action, String actor, boolean automatic) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("consumer", gate.name());
        details.put("instance", instance.id());
        details.put("automatic", automatic);
        details.put("reason", gate.reason());
        publishEvent(action, instance.id() + "/" + gate.name(), actor, details);
        publish(gate);
    }

    private void publishEvent(String action, String subject, String actor, Map<String, Object> details) {
        try {
            events.publish(ENTITY, action, subject, EventData.action(actor, details));
        } catch (RuntimeException e) {
            LOG.warn("Event consumer.{} not published: {}", action, e.getMessage());
        }
    }

    /** Publishes the status of every gate. */
    void publishAll() {
        gates.forEach(this::publish);
    }

    private void publish(ConsumerGate gate) {
        Producer<ConsumerStatus> producer = statusProducer;
        if (producer == null) {
            return;
        }
        producer.newMessage().key(key(gate)).value(gate.status(instance.id(), lastCheck)).sendAsync()
                .exceptionally(error -> {
                    LOG.debug("Consumer status not published: {}", error.getMessage());
                    return null;
                });
    }

    private String key(ConsumerGate gate) {
        return instance.id() + "/" + gate.name();
    }

    private void openStatusProducer() {
        if (closed) {
            return;
        }
        client.newProducer(Schema.JSON(ConsumerStatus.class))
                .topic(CatacumbasTopics.consumers(app))
                .createAsync()
                .whenComplete((producer, error) -> {
                    if (error != null) {
                        LOG.warn("Consumer status topic not open yet, retrying: {}", error.getMessage());
                        later(settings.statusInterval().toMillis(), this::openStatusProducer);
                        return;
                    }
                    statusProducer = producer;
                    publishAll();
                });
    }

    private void openCommandReader() {
        if (closed) {
            return;
        }
        client.newReader(Schema.JSON(ConsumerCommand.class))
                .topic(CatacumbasTopics.consumerCommands(app))
                .startMessageId(MessageId.latest)
                .readerListener((reader, message) -> onCommand(message))
                .createAsync()
                .whenComplete((reader, error) -> {
                    if (error != null) {
                        LOG.warn("Consumer commands topic not read yet, retrying: {}", error.getMessage());
                        later(settings.statusInterval().toMillis(), this::openCommandReader);
                        return;
                    }
                    commandReader = reader;
                });
    }

    private void onCommand(Message<ConsumerCommand> message) {
        try {
            handle(message.getValue());
        } catch (RuntimeException e) {
            LOG.warn("Consumer command {} not handled: {}", message.getMessageId(), e.getMessage());
        }
    }

    private void every(long millis, Runnable task) {
        try {
            scheduler.scheduleWithFixedDelay(() -> {
                try {
                    task.run();
                } catch (RuntimeException e) {
                    LOG.warn("Consumer control task failed: {}", e.getMessage());
                }
            }, millis, millis, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            LOG.debug("Consumer control not scheduled, shutting down");
        }
    }

    private void later(long millis, Runnable task) {
        try {
            scheduler.schedule(task, millis, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            LOG.debug("Consumer control retry dropped, shutting down");
        }
    }

    /** Writes a tombstone for every gate and closes the producer and the reader. */
    @Override
    public void close() {
        closed = true;
        Producer<ConsumerStatus> producer = statusProducer;
        if (producer != null) {
            for (ConsumerGate gate : gates) {
                try {
                    producer.newMessage().key(key(gate)).value(null).send();
                } catch (Exception e) {
                    LOG.debug("Consumer status tombstone not written: {}", e.getMessage());
                }
            }
            producer.closeAsync();
        }
        Reader<ConsumerCommand> reader = commandReader;
        if (reader != null) {
            reader.closeAsync();
        }
    }
}
