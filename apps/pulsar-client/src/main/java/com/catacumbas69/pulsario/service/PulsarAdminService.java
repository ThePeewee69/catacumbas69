package com.catacumbas69.pulsario.service;

import com.catacumbas69.pulsario.dto.MessageDTO;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.api.*;
import org.apache.pulsar.common.policies.data.TopicStats;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class PulsarAdminService {

    @Autowired
    private PulsarAdmin admin;

    @Autowired
    private PulsarClient client;

    // ─── Tenants & Namespaces ─────────────────────────────────────────────────

    public List<String> listTenants() throws Exception {
        return admin.tenants().getTenants();
    }

    public List<String> listNamespaces(String tenant) throws Exception {
        List<String> ns = admin.namespaces().getNamespaces(tenant);
        // Return only the namespace part (strip "tenant/")
        return ns.stream()
                .map(n -> n.contains("/") ? n.substring(n.indexOf('/') + 1) : n)
                .collect(Collectors.toList());
    }

    // ─── Topics ───────────────────────────────────────────────────────────────

    public List<String> listTopics(String tenant, String namespace) throws Exception {
        String ns = tenant + "/" + namespace;
        List<String> persistent = admin.topics().getList(ns);
        if (persistent == null) return List.of();
        // Return only the topic name (strip "persistent://tenant/namespace/")
        return persistent.stream()
                .map(t -> {
                    String[] parts = t.split("/");
                    return parts[parts.length - 1];
                })
                .collect(Collectors.toList());
    }

    public void createTopic(String fullTopic) throws Exception {
        admin.topics().createNonPartitionedTopic(fullTopic);
    }

    public void deleteTopic(String fullTopic) throws Exception {
        admin.topics().delete(fullTopic, true);
    }

    // ─── Stats ────────────────────────────────────────────────────────────────

    public TopicStats getTopicStats(String fullTopic) throws Exception {
        return admin.topics().getStats(fullTopic);
    }

    // ─── Subscriptions ────────────────────────────────────────────────────────

    public List<String> listSubscriptions(String fullTopic) throws Exception {
        return admin.topics().getSubscriptions(fullTopic);
    }

    public void createSubscription(String fullTopic, String subscription) throws Exception {
        admin.topics().createSubscription(fullTopic, subscription, MessageId.earliest);
    }

    public void deleteSubscription(String fullTopic, String subscription) throws Exception {
        admin.topics().deleteSubscription(fullTopic, subscription);
    }

    // ─── Produce ──────────────────────────────────────────────────────────────

    public String produce(String fullTopic, String payload, String key) throws Exception {
        try (Producer<byte[]> producer = client.newProducer()
                .topic(fullTopic)
                .enableBatching(false)
                .create()) {

            TypedMessageBuilder<byte[]> msg = producer.newMessage()
                    .value(payload.getBytes(StandardCharsets.UTF_8));

            if (key != null && !key.isBlank()) {
                msg.key(key);
            }

            MessageId msgId = msg.send();
            return msgId.toString();
        }
    }

    // ─── Peek Messages (backlog) ──────────────────────────────────────────────

    public List<MessageDTO> peekMessages(String fullTopic, String subscription, int count) throws Exception {
        var messages = admin.topics().peekMessages(fullTopic, subscription, Math.min(count, 100));
        return messages.stream().map(m -> {
            byte[] data = m.getData();
            boolean isBinary = false;
            String payloadStr;

            // Try to decode as UTF-8; fall back to base64
            try {
                payloadStr = new String(data, StandardCharsets.UTF_8);
                // Sanity check: if it contains many replacement chars, it's probably binary
                long replacements = payloadStr.chars().filter(c -> c == '�').count();
                if (replacements > data.length * 0.1) {
                    payloadStr = Base64.getEncoder().encodeToString(data);
                    isBinary = true;
                }
            } catch (Exception e) {
                payloadStr = Base64.getEncoder().encodeToString(data);
                isBinary = true;
            }

            return new MessageDTO(
                    m.getMessageId().toString(),
                    payloadStr,
                    m.getKey(),
                    m.getPublishTime(),
                    m.getProperties(),
                    isBinary
            );
        }).collect(Collectors.toList());
    }
}
