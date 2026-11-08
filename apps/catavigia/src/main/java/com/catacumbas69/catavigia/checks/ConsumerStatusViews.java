package com.catacumbas69.catavigia.checks;

import com.catacumbas69.catacommons.CatacommonsScheduler;
import com.catacumbas69.catacommons.consumers.ConsumerStatus;
import com.catacumbas69.catacommons.pulsar.CatacumbasTopics;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
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
 * (orch.md decision 67), kept open on every replica so whichever claims the {@code consumers} task can read it.
 */
@Component
public class ConsumerStatusViews implements ApplicationListener<ApplicationReadyEvent>, DisposableBean {

    /** Apps whose consumers are watched. */
    public static final List<String> APPS = List.of("cataudit", "catametricas", "catavigia");

    private static final Logger LOG = LoggerFactory.getLogger(ConsumerStatusViews.class);
    private static final long RETRY_SECONDS = 10;

    private final PulsarClient client;
    private final CatacommonsScheduler scheduler;
    private final Map<String, TableView<ConsumerStatus>> views = new ConcurrentHashMap<>();
    private volatile boolean closed;

    /**
     * Creates the views; nothing connects until the application is ready.
     *
     * @param client Pulsar client
     * @param scheduler runs the retries
     */
    public ConsumerStatusViews(PulsarClient client, CatacommonsScheduler scheduler) {
        this.client = client;
        this.scheduler = scheduler;
    }

    /**
     * Opens the views in the background.
     *
     * @param event the application is ready
     */
    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        APPS.forEach(this::open);
    }

    /**
     * Returns every reported consumer replica.
     *
     * @return the statuses
     * @throws SourceUnavailableException while an app's topic has not been read yet
     */
    public List<ConsumerStatus> all() throws SourceUnavailableException {
        List<ConsumerStatus> all = new ArrayList<>();
        for (String app : APPS) {
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
            LOG.debug("Not retried, shutting down");
        }
    }
}
