package com.catacumbas69.catapulsar.service;

import java.util.List;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.common.policies.data.TopicStats;
import org.springframework.stereotype.Service;

/**
 * Tenants, namespaces, topics, statistics and subscriptions through the Pulsar admin API.
 */
@Service
public class PulsarAdminService {

    private final PulsarAdmin admin;
    private final ProducerCache producers;

    /**
     * Creates the service.
     *
     * @param admin Pulsar admin client
     * @param producers producer cache, cleared for deleted topics
     */
    public PulsarAdminService(PulsarAdmin admin, ProducerCache producers) {
        this.admin = admin;
        this.producers = producers;
    }

    /**
     * Lists tenants.
     *
     * @return tenant names
     * @throws PulsarAdminException if Pulsar rejects the request
     */
    public List<String> listTenants() throws PulsarAdminException {
        return admin.tenants().getTenants();
    }

    /**
     * Lists the namespaces of a tenant.
     *
     * @param tenant tenant name
     * @return namespace names without the tenant prefix
     * @throws PulsarAdminException if Pulsar rejects the request
     */
    public List<String> listNamespaces(String tenant) throws PulsarAdminException {
        return admin.namespaces().getNamespaces(tenant).stream()
                .map(PulsarAdminService::lastSegment)
                .toList();
    }

    /**
     * Lists the persistent topics of a namespace.
     *
     * @param tenant tenant name
     * @param namespace namespace name
     * @return topic names without the {@code persistent://tenant/namespace/} prefix
     * @throws PulsarAdminException if Pulsar rejects the request
     */
    public List<String> listTopics(String tenant, String namespace) throws PulsarAdminException {
        List<String> topics = admin.topics().getList(tenant + "/" + namespace);
        if (topics == null) {
            return List.of();
        }
        return topics.stream().map(PulsarAdminService::lastSegment).toList();
    }

    /**
     * Creates a non-partitioned topic.
     *
     * @param topic fully-qualified topic
     * @throws PulsarAdminException if Pulsar rejects the request (e.g. it already exists)
     */
    public void createTopic(String topic) throws PulsarAdminException {
        admin.topics().createNonPartitionedTopic(topic);
    }

    /**
     * Deletes a topic, even if it has producers or subscriptions.
     *
     * @param topic fully-qualified topic
     * @throws PulsarAdminException if Pulsar rejects the request
     */
    public void deleteTopic(String topic) throws PulsarAdminException {
        producers.remove(topic);
        admin.topics().delete(topic, true);
    }

    /**
     * Returns topic statistics.
     *
     * @param topic fully-qualified topic
     * @return the statistics
     * @throws PulsarAdminException if Pulsar rejects the request
     */
    public TopicStats getTopicStats(String topic) throws PulsarAdminException {
        return admin.topics().getStats(topic);
    }

    /**
     * Lists the subscriptions of a topic.
     *
     * @param topic fully-qualified topic
     * @return subscription names
     * @throws PulsarAdminException if Pulsar rejects the request
     */
    public List<String> listSubscriptions(String topic) throws PulsarAdminException {
        return admin.topics().getSubscriptions(topic);
    }

    /**
     * Creates a subscription positioned at the earliest message.
     *
     * @param topic fully-qualified topic
     * @param subscription subscription name
     * @throws PulsarAdminException if Pulsar rejects the request
     */
    public void createSubscription(String topic, String subscription) throws PulsarAdminException {
        admin.topics().createSubscription(topic, subscription, MessageId.earliest);
    }

    /**
     * Deletes a subscription.
     *
     * @param topic fully-qualified topic
     * @param subscription subscription name
     * @throws PulsarAdminException if Pulsar rejects the request
     */
    public void deleteSubscription(String topic, String subscription) throws PulsarAdminException {
        admin.topics().deleteSubscription(topic, subscription);
    }

    /**
     * Builds the fully-qualified name of a persistent topic.
     *
     * @param tenant tenant name
     * @param namespace namespace name
     * @param topic topic name
     * @return {@code persistent://tenant/namespace/topic}
     */
    public static String persistentTopic(String tenant, String namespace, String topic) {
        return "persistent://" + tenant + "/" + namespace + "/" + topic;
    }

    private static String lastSegment(String name) {
        return name.substring(name.lastIndexOf('/') + 1);
    }
}
