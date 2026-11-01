package com.catacumbas69.catametricas.service;

import com.catacumbas69.catametricas.config.CatametricasProperties;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reads Pulsar: the list of namespaces (for the task scheduler) and the statistics of every topic of one
 * namespace (for one task), with at most {@code catametricas.collect.concurrency} calls in flight (PERF-6). One
 * failing tenant or topic does not stop the others (RES-12); it is reported in the result instead.
 */
@Component
public class PulsarStatsReader {

    private static final Logger LOG = LoggerFactory.getLogger(PulsarStatsReader.class);

    private final PulsarAdmin admin;
    private final int concurrency;
    private final boolean skipSystemTopics;

    /**
     * Creates the reader.
     *
     * @param admin Pulsar admin client
     * @param properties application properties
     */
    public PulsarStatsReader(PulsarAdmin admin, CatametricasProperties properties) {
        this.admin = admin;
        this.concurrency = properties.collect().concurrency();
        this.skipSystemTopics = properties.collect().skipSystemTopics();
    }

    /**
     * Lists every namespace of every tenant.
     *
     * @return full namespace names ({@code tenant/namespace}), sorted; a tenant whose namespaces cannot be
     *         listed is skipped with a warning
     * @throws PulsarAdminException if not even the tenant list can be read
     */
    public List<String> namespaces() throws PulsarAdminException {
        List<String> namespaces = new ArrayList<>();
        for (String tenant : admin.tenants().getTenants()) {
            try {
                namespaces.addAll(admin.namespaces().getNamespaces(tenant));
            } catch (PulsarAdminException | RuntimeException e) {
                LOG.warn("Namespaces of tenant {} not read: {}", tenant, e.getMessage());
            }
        }
        Collections.sort(namespaces);
        return namespaces;
    }

    /**
     * Reads the statistics of every topic of a namespace.
     *
     * @param namespace full namespace, {@code tenant/namespace}
     * @return samples and the topics that could not be read
     * @throws PulsarAdminException if the namespace's topics cannot be listed
     */
    public PulsarReading read(String namespace) throws PulsarAdminException {
        List<String> listed = admin.topics().getList(namespace);
        List<String> topics = listed == null ? List.of() : listed.stream().filter(this::shouldCollect).toList();
        List<TopicSample> samples = Collections.synchronizedList(new ArrayList<>());
        Set<String> failed = ConcurrentHashMap.newKeySet();
        Semaphore permits = new Semaphore(concurrency);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (String topic : topics) {
                executor.submit(() -> {
                    permits.acquireUninterruptibly();
                    try {
                        samples.add(TopicSample.of(topic, admin.topics().getStats(topic)));
                    } catch (PulsarAdminException | RuntimeException e) {
                        failed.add(topic);
                        LOG.warn("Statistics of {} not read: {}", topic, e.getMessage());
                    } finally {
                        permits.release();
                    }
                });
            }
        }
        return new PulsarReading(namespace, List.copyOf(samples), Set.copyOf(failed));
    }

    private boolean shouldCollect(String topic) {
        try {
            return !(skipSystemTopics && TopicNames.parse(topic).isSystemTopic());
        } catch (IllegalArgumentException e) {
            LOG.warn("Skipping topic with unexpected name {}", topic);
            return false;
        }
    }
}
