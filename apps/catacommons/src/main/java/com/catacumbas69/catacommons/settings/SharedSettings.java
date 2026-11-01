package com.catacumbas69.catacommons.settings;

import com.catacumbas69.catacommons.pulsar.CatacumbasTopics;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.TableView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;

/**
 * The shared settings, read from the compacted topic {@code catacumbas69/catapulsar/settings} (ADR 0011).
 *
 * <p>A Pulsar {@link TableView} reads the whole topic when the app starts and then follows it, so every replica
 * of every app holds the latest value of every key in memory, and sees a change within seconds. Nothing is
 * consumed or acknowledged. A tombstone (empty value) removes the key, which means "use the default".
 *
 * <p>Until the topic has been read (for example while Pulsar is down at start-up) {@link #get(String)} is empty
 * and callers use their configured defaults; opening the view is retried in the background.
 */
public class SharedSettings implements ApplicationListener<ApplicationReadyEvent>, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(SharedSettings.class);

    private final PulsarClient client;
    private final ScheduledExecutorService scheduler;
    private final Duration retryInterval;
    private final Map<String, String> values = new ConcurrentHashMap<>();
    private final Map<String, List<Consumer<String>>> listeners = new ConcurrentHashMap<>();
    private volatile TableView<SettingValue> view;
    private volatile boolean closed;

    /**
     * Creates the reader; nothing connects until the application is ready.
     *
     * @param client Pulsar client
     * @param scheduler runs retries
     * @param retryInterval wait before trying to open the view again
     */
    public SharedSettings(PulsarClient client, ScheduledExecutorService scheduler, Duration retryInterval) {
        this.client = client;
        this.scheduler = scheduler;
        this.retryInterval = retryInterval;
    }

    /**
     * Returns a setting's current value.
     *
     * @param key setting key, e.g. {@code catametricas.collect-interval-seconds}
     * @return the value, or empty for "use the default" (no value, tombstone, or topic not read yet)
     */
    public Optional<String> get(String key) {
        return Optional.ofNullable(values.get(key));
    }

    /**
     * Calls {@code listener} with the current value (if any) and again on every change of the key.
     *
     * @param key setting key
     * @param listener receives the new value, or {@code null} when the key was removed (back to the default)
     */
    public void listen(String key, Consumer<String> listener) {
        listeners.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(listener);
        String current = values.get(key);
        if (current != null) {
            listener.accept(current);
        }
    }

    /**
     * Tells whether the topic has been read at least once.
     *
     * @return {@code true} once the view is open
     */
    public boolean isReady() {
        return view != null;
    }

    /**
     * Applies one value read from the topic and notifies the listeners of its key. Called by the view; public
     * so tests can feed values.
     *
     * @param key setting key
     * @param value the value, or {@code null} for a tombstone
     */
    public void apply(String key, SettingValue value) {
        String text = value == null ? null : value.value();
        if (text == null) {
            values.remove(key);
        } else {
            values.put(key, text);
        }
        for (Consumer<String> listener : listeners.getOrDefault(key, List.of())) {
            try {
                listener.accept(text);
            } catch (RuntimeException e) {
                LOG.warn("Setting {} not applied by a listener: {}", key, e.getMessage());
            }
        }
    }

    /**
     * Opens the view in the background.
     *
     * @param event the application is ready
     */
    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        open();
    }

    private void open() {
        if (closed) {
            return;
        }
        client.newTableView(Schema.JSON(SettingValue.class))
                .topic(CatacumbasTopics.settings())
                .createAsync()
                .whenComplete((opened, error) -> {
                    if (error != null) {
                        LOG.warn("Settings topic not read yet, using defaults; retrying in {} s: {}",
                                retryInterval.toSeconds(), error.getMessage());
                        retryLater();
                        return;
                    }
                    view = opened;
                    opened.forEachAndListen(this::apply);
                    LOG.info("Reading {} ({} setting(s))", CatacumbasTopics.settings(), values.size());
                });
    }

    private void retryLater() {
        try {
            scheduler.schedule(this::open, retryInterval.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            LOG.debug("Settings view not retried, shutting down");
        }
    }

    /** Closes the view. */
    @Override
    public void close() {
        closed = true;
        TableView<SettingValue> current = view;
        if (current != null) {
            current.closeAsync();
        }
    }
}
