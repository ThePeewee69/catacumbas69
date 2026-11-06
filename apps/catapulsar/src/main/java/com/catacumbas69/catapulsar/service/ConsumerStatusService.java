package com.catacumbas69.catapulsar.service;

import com.catacumbas69.catacommons.CatacommonsScheduler;
import com.catacumbas69.catacommons.consumers.ConsumerCommand;
import com.catacumbas69.catacommons.consumers.ConsumerStatus;
import com.catacumbas69.catacommons.pulsar.CatacumbasTopics;
import com.catacumbas69.catapulsar.dto.ConsumerStatusResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.TableView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * The apps' consumers for the Consumers panel (ADR 0015, orch.md decision 67): reads each app's compacted
 * {@code catacumbas69/<app>/consumers} with a {@link TableView}, and publishes the panel's commands on
 * {@code catacumbas69/<app>/consumer-commands}. Each request is audited as {@code consumer.<action>-requested};
 * the app records what it did. Everything goes through Pulsar, so it works while MongoDB is down.
 */
@Service
public class ConsumerStatusService implements ApplicationListener<ApplicationReadyEvent>, DisposableBean {

    /** Apps whose consumers are controlled. */
    public static final List<String> APPS = List.of("cataudit", "catametricas");

    /** A replica that has not reported for this long is shown as gone. */
    static final Duration STALE_AFTER = Duration.ofSeconds(30);

    private static final Logger LOG = LoggerFactory.getLogger(ConsumerStatusService.class);
    private static final long SEND_TIMEOUT_SECONDS = 10;
    private static final long RETRY_SECONDS = 10;

    private final PulsarClient client;
    private final CatacommonsScheduler scheduler;
    private final AuditService audit;
    private final Clock clock;
    private final Map<String, TableView<ConsumerStatus>> views = new ConcurrentHashMap<>();
    private final Map<String, Producer<ConsumerCommand>> producers = new ConcurrentHashMap<>();
    private volatile boolean closed;

    /**
     * Creates the service.
     *
     * @param client Pulsar client
     * @param scheduler runs the retries
     * @param audit audit log
     * @param clock time source
     */
    public ConsumerStatusService(PulsarClient client, CatacommonsScheduler scheduler, AuditService audit,
                                 Clock clock) {
        this.client = client;
        this.scheduler = scheduler;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Opens the status views in the background.
     *
     * @param event the application is ready
     */
    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        APPS.forEach(this::open);
    }

    /**
     * Returns every consumer of every app, by app, consumer and instance.
     *
     * @return the consumers; an app whose topic was not read yet has none
     */
    public List<ConsumerStatusResponse> list() {
        Instant now = clock.instant();
        List<ConsumerStatusResponse> result = new ArrayList<>();
        views.values().forEach(view -> view.values().forEach(status ->
                result.add(ConsumerStatusResponse.of(status, isStale(status, now)))));
        result.sort(Comparator.comparing(ConsumerStatusResponse::app).thenComparing(ConsumerStatusResponse::consumer)
                .thenComparing(ConsumerStatusResponse::instance));
        return result;
    }

    /**
     * Publishes a command for an app's consumers and audits the request.
     *
     * @param app app name
     * @param instance a replica or {@code *}
     * @param consumer a consumer or {@code *}
     * @param action {@code pause}, {@code resume} or {@code check}
     * @param user client IP address
     * @throws ResponseStatusException {@code 400} for an unknown app or action, {@code 503} if Pulsar refuses
     */
    public void send(String app, String instance, String consumer, String action, String user) {
        if (!APPS.contains(app)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown app '" + app + "'");
        }
        AuditAction audited = switch (action) {
            case ConsumerCommand.PAUSE -> AuditAction.PAUSE_CONSUMER;
            case ConsumerCommand.RESUME -> AuditAction.RESUME_CONSUMER;
            case ConsumerCommand.CHECK -> AuditAction.CHECK_CONSUMER;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown action '" + action + "'");
        };
        ConsumerCommand command = new ConsumerCommand(UUID.randomUUID().toString(), instance, consumer, action, user,
                clock.instant().toString());
        try {
            producer(app).sendAsync(command).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable(app, e);
        } catch (Exception e) {
            throw unavailable(app, e);
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("app", app);
        details.put("instance", instance);
        details.put("consumer", consumer);
        audit.record(user, audited, CatacumbasTopics.consumerCommands(app), details);
    }

    static boolean isStale(ConsumerStatus status, Instant now) {
        try {
            return status.updatedAt() == null || Instant.parse(status.updatedAt()).isBefore(now.minus(STALE_AFTER));
        } catch (DateTimeParseException e) {
            return true;
        }
    }

    private Producer<ConsumerCommand> producer(String app) throws PulsarClientException {
        Producer<ConsumerCommand> existing = producers.get(app);
        if (existing != null && existing.isConnected()) {
            return existing;
        }
        Producer<ConsumerCommand> created = client.newProducer(Schema.JSON(ConsumerCommand.class))
                .topic(CatacumbasTopics.consumerCommands(app))
                .sendTimeout((int) SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .create();
        Producer<ConsumerCommand> previous = producers.put(app, created);
        if (previous != null) {
            previous.closeAsync();
        }
        return created;
    }

    private static ResponseStatusException unavailable(String app, Exception e) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Command not sent to " + app + " (Pulsar): " + e.getMessage(), e);
    }

    private void open(String app) {
        if (closed) {
            return;
        }
        client.newTableView(Schema.JSON(ConsumerStatus.class))
                .topic(CatacumbasTopics.consumers(app))
                .createAsync()
                .whenComplete((view, error) -> {
                    if (error != null) {
                        LOG.warn("Consumer status of {} not read yet, retrying in {} s: {}", app, RETRY_SECONDS,
                                error.getMessage());
                        later(() -> open(app));
                        return;
                    }
                    views.put(app, view);
                });
    }

    private void later(Runnable task) {
        try {
            scheduler.executor().schedule(task, RETRY_SECONDS, TimeUnit.SECONDS);
        } catch (RejectedExecutionException e) {
            LOG.debug("Consumer status view not retried, shutting down");
        }
    }

    /** Closes the views and producers. */
    @Override
    public void destroy() {
        closed = true;
        views.values().forEach(TableView::closeAsync);
        producers.values().forEach(Producer::closeAsync);
    }
}
