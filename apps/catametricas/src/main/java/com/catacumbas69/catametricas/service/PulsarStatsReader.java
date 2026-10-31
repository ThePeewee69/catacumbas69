package com.catacumbas69.catametricas.service;

import com.catacumbas69.catametricas.config.CatametricasProperties;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
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
 * Lists every topic of every namespace of every tenant and reads its statistics, with at most
 * {@code catametricas.collect.concurrency} calls in flight (PERF-6). One failing namespace or topic does not
 * stop the others (RES-12); it is reported in the result instead.
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
     * Reads the statistics of all topics.
     *
     * @return samples and what could not be read
     * @throws PulsarAdminException if not even the tenant list can be read
     */
    public PulsarReading read() throws PulsarAdminException {
        List<String> topics = new ArrayList<>();
        Set<String> listedNamespaces = new HashSet<>();
        for (String tenant : admin.tenants().getTenants()) {
            for (String namespace : namespacesOf(tenant)) {
                List<String> listed = topicsOf(namespace);
                if (listed != null) {
                    listedNamespaces.add(namespace);
                    listed.stream().filter(this::shouldCollect).forEach(topics::add);
                }
            }
        }
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
        return new PulsarReading(List.copyOf(samples), Set.copyOf(listedNamespaces), Set.copyOf(failed));
    }

    private List<String> namespacesOf(String tenant) {
        try {
            return admin.namespaces().getNamespaces(tenant);
        } catch (PulsarAdminException | RuntimeException e) {
            LOG.warn("Namespaces of tenant {} not read: {}", tenant, e.getMessage());
            return List.of();
        }
    }

    /** Returns the topics of a namespace, or {@code null} if they could not be listed. */
    private List<String> topicsOf(String namespace) {
        try {
            List<String> topics = admin.topics().getList(namespace);
            return topics == null ? List.of() : topics;
        } catch (PulsarAdminException | RuntimeException e) {
            LOG.warn("Topics of namespace {} not read: {}", namespace, e.getMessage());
            return null;
        }
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
