package com.catacumbas69.catacommons.pulsar;

import com.catacumbas69.catacommons.CatacommonsProperties;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.apache.pulsar.common.policies.data.BacklogQuota;
import org.apache.pulsar.common.policies.data.RetentionPolicies;
import org.apache.pulsar.common.policies.data.TenantInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;

/**
 * Creates what this app needs in Pulsar once it has started (ADR 0008): the {@code catacumbas69} tenant
 * if missing, the app's namespace with its policies, and its topics with their schemas.
 *
 * <p>Every step is idempotent ("already exists" counts as success) and policies are set on every start,
 * so N replicas starting together are safe and a policy changed by hand is put back. It runs in the
 * background, so the app starts even when Pulsar is down; a failed attempt is retried with back-off until
 * it succeeds.
 */
public class PulsarProvisioner implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger LOG = LoggerFactory.getLogger(PulsarProvisioner.class);
    private static final long BYTES_PER_MB = 1024L * 1024L;

    private final PulsarAdmin admin;
    private final String app;
    private final CatacommonsProperties.Provisioning settings;
    private final List<TopicSpec> topics;
    private final ScheduledExecutorService scheduler;
    private volatile boolean provisioned;

    /**
     * Creates the provisioner.
     *
     * @param admin Pulsar admin client
     * @param properties catacommons configuration
     * @param topics topics to create in this app's namespace
     * @param scheduler runs the attempts
     */
    public PulsarProvisioner(PulsarAdmin admin, CatacommonsProperties properties, List<TopicSpec> topics,
                             ScheduledExecutorService scheduler) {
        this.admin = admin;
        this.app = properties.app();
        this.settings = properties.provisioning();
        this.topics = List.copyOf(topics);
        this.scheduler = scheduler;
    }

    /**
     * Starts provisioning in the background.
     *
     * @param event the application is ready
     */
    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        schedule(Duration.ZERO, settings.retryInterval());
    }

    /**
     * Tells whether provisioning has completed in this process.
     *
     * @return {@code true} once every step succeeded
     */
    public boolean isProvisioned() {
        return provisioned;
    }

    private void schedule(Duration delay, Duration nextDelay) {
        try {
            scheduler.schedule(() -> attempt(nextDelay), delay.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            LOG.debug("Provisioning not scheduled, shutting down");
        }
    }

    private void attempt(Duration nextDelay) {
        try {
            provision();
            provisioned = true;
            LOG.info("Pulsar namespace {} and {} topic(s) ready", CatacumbasTopics.namespace(app), topics.size());
        } catch (PulsarAdminException | RuntimeException e) {
            LOG.warn("Pulsar provisioning failed, retrying in {} s: {}", nextDelay.toSeconds(), e.getMessage());
            Duration doubled = nextDelay.multipliedBy(2);
            schedule(nextDelay, doubled.compareTo(settings.maxRetryInterval()) > 0
                    ? settings.maxRetryInterval() : doubled);
        }
    }

    /**
     * Runs every step once.
     *
     * @throws PulsarAdminException if a step fails for any reason other than "already exists"
     */
    void provision() throws PulsarAdminException {
        ensureTenant();
        String namespace = CatacumbasTopics.namespace(app);
        ignoreConflict(() -> admin.namespaces().createNamespace(namespace));
        admin.namespaces().setRetention(namespace, new RetentionPolicies(
                minutes(settings.eventsRetentionTime()), megabytes(settings.eventsRetentionSize().toBytes())));
        admin.namespaces().setBacklogQuota(namespace, BacklogQuota.builder()
                .limitSize(settings.eventsBacklogQuota().toBytes())
                .retentionPolicy(BacklogQuota.RetentionPolicy.producer_exception)
                .build());
        for (TopicSpec spec : topics) {
            createTopic(CatacumbasTopics.topic(app, spec.localName()), spec);
        }
    }

    private void ensureTenant() throws PulsarAdminException {
        if (admin.tenants().getTenants().contains(CatacumbasTopics.TENANT)) {
            return;
        }
        TenantInfo info = TenantInfo.builder()
                .allowedClusters(new HashSet<>(admin.clusters().getClusters()))
                .build();
        ignoreConflict(() -> admin.tenants().createTenant(CatacumbasTopics.TENANT, info));
        LOG.info("Created Pulsar tenant {}", CatacumbasTopics.TENANT);
    }

    private void createTopic(String topic, TopicSpec spec) throws PulsarAdminException {
        ignoreConflict(() -> admin.topics().createNonPartitionedTopic(topic));
        if (spec.compacted()) {
            admin.topicPolicies().setRetention(topic, new RetentionPolicies(-1, -1));
            admin.topicPolicies().setCompactionThreshold(topic, settings.compactionThreshold().toBytes());
        }
        if (spec.schema() != null) {
            admin.schemas().createSchema(topic, spec.schema().getSchemaInfo());
        }
    }

    private static void ignoreConflict(AdminCall call) throws PulsarAdminException {
        try {
            call.run();
        } catch (PulsarAdminException.ConflictException ignored) {
            // already exists: nothing to do
        }
    }

    private static int minutes(Duration duration) {
        return (int) Math.min(Integer.MAX_VALUE, duration.toMinutes());
    }

    private static int megabytes(long bytes) {
        return (int) Math.min(Integer.MAX_VALUE, bytes / BYTES_PER_MB);
    }

    /** An admin call that may throw. */
    @FunctionalInterface
    private interface AdminCall {
        void run() throws PulsarAdminException;
    }
}
