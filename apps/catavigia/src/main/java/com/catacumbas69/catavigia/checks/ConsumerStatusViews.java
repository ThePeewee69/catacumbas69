package com.catacumbas69.catavigia.checks;

import com.catacumbas69.catacommons.CatacommonsScheduler;
import com.catacumbas69.catacommons.consumers.ConsumerApps;
import com.catacumbas69.catacommons.consumers.ConsumerStatus;
import com.catacumbas69.catacommons.pulsar.CatacumbasTopics;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.TableView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/**
 * The status of the apps' consumers, read from each app's compacted {@code catacumbas69/<app>/consumers} topic
 * (orch.md decision 67), kept open on every replica so whichever claims the {@code consumers} task can read it. The
 * apps are found, not named: every minute, those whose namespace has a {@code consumers} topic
 * ({@link ConsumerApps}, decision 118).
 */
@Component
public class ConsumerStatusViews implements ApplicationListener<ApplicationReadyEvent>, DisposableBean {

    /** How often the apps are looked for again. */
    static final long DISCOVER_SECONDS = 60;

    private static final Logger LOG = LoggerFactory.getLogger(ConsumerStatusViews.class);
    private static final long RETRY_SECONDS = 10;

    private final PulsarClient client;
    private final PulsarAdmin admin;
    private final CatacommonsScheduler scheduler;
    private final Map<String, TableView<ConsumerStatus>> views = new ConcurrentHashMap<>();
    private final Set<String> apps = ConcurrentHashMap.newKeySet();
    private volatile boolean discovered;
    private volatile boolean closed;

    /**
     * Creates the views; nothing connects until the application is ready.
     *
     * @param client Pulsar client
     * @param admin Pulsar admin client, to find the apps
     * @param scheduler runs the retries
     */
    public ConsumerStatusViews(PulsarClient client, PulsarAdmin admin, CatacommonsScheduler scheduler) {
        this.client = client;
        this.admin = admin;
        this.scheduler = scheduler;
    }

    /**
     * Looks for the apps now and every minute, in the background, and opens the views of new ones.
     *
     * @param event the application is ready
     */
    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        try {
            scheduler.executor().scheduleWithFixedDelay(this::discover, 0, DISCOVER_SECONDS, TimeUnit.SECONDS);
        } catch (RejectedExecutionException e) {
            LOG.debug("Consumer apps not looked for, shutting down");
        }
    }

    /**
     * Looks for the apps with a consumers topic and opens the views of the new ones. When Pulsar does not answer,
     * the apps already found stay.
     */
    void discover() {
        if (closed) {
            return;
        }
        try {
            for (String app : ConsumerApps.discover(admin)) {
                if (apps.add(app)) {
                    LOG.info("Consumers of {} found", app);
                    openOrRetry(app);
                }
            }
            discovered = true;
        } catch (PulsarAdminException | RuntimeException e) {
            LOG.warn("Apps with consumers not looked for, retrying in {} s: {}", DISCOVER_SECONDS, e.getMessage());
        }
    }

    /**
     * Returns every reported consumer replica.
     *
     * @return the statuses
     * @throws SourceUnavailableException before the apps were first found, or while an app's topic has not been
     *     read yet
     */
    public List<ConsumerStatus> all() throws SourceUnavailableException {
        if (!discovered) {
            throw new SourceUnavailableException("Apps with consumers not found yet", null);
        }
        List<ConsumerStatus> all = new ArrayList<>();
        for (String app : apps) {
            TableView<ConsumerStatus> view = views.get(app);
            if (view == null) {
                throw new SourceUnavailableException("Consumer status of " + app + " not read yet", null);
            }
            all.addAll(view.values());
        }
        return all;
    }

    /** Closes the views on shutdown. */
    @Override
    public void destroy() {
        closed = true;
        views.values().forEach(TableView::closeAsync);
    }

    private void openOrRetry(String app) {
        try {
            open(app);
        } catch (RuntimeException e) {
            LOG.warn("Consumer status of {} not opened, retrying in {} s: {}", app, RETRY_SECONDS, e.getMessage());
            later(() -> openOrRetry(app));
        }
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
                        later(() -> openOrRetry(app));
                        return;
                    }
                    views.put(app, view);
                });
    }

    private void later(Runnable task) {
        try {
            scheduler.executor().schedule(task, RETRY_SECONDS, TimeUnit.SECONDS);
        } catch (RejectedExecutionException e) {
            LOG.debug("Not retried, shutting down");
        }
    }
}
