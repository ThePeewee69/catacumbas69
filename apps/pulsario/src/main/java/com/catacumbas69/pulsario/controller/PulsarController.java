package com.catacumbas69.pulsario.controller;

import static com.catacumbas69.pulsario.service.PulsarAdminService.persistentTopic;

import com.catacumbas69.pulsario.dto.MessageDTO;
import com.catacumbas69.pulsario.dto.ProduceRequest;
import com.catacumbas69.pulsario.dto.ProduceResponse;
import com.catacumbas69.pulsario.dto.PulsarName;
import com.catacumbas69.pulsario.service.AuditAction;
import com.catacumbas69.pulsario.service.AuditService;
import com.catacumbas69.pulsario.service.MessageService;
import com.catacumbas69.pulsario.service.PulsarAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.common.policies.data.TopicStats;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pulsar administration for the UI: tenants, namespaces, topics, subscriptions and messages. Every
 * name in the path is validated before it reaches Pulsar (SEC-4); mutations are audited (OBS-6).
 */
@Tag(name = "Pulsar")
@RestController
@RequestMapping("/api/v1")
public class PulsarController {

    private static final String TOPIC_PATH = "/topics/{tenant}/{namespace}/{topic}";
    private static final String SUBSCRIPTION_PATH = TOPIC_PATH + "/subscriptions/{subscription}";

    private final PulsarAdminService admin;
    private final MessageService messages;
    private final AuditService audit;

    /**
     * Creates the controller.
     *
     * @param admin Pulsar administration
     * @param messages produce, peek and skip
     * @param audit audit log
     */
    public PulsarController(PulsarAdminService admin, MessageService messages, AuditService audit) {
        this.admin = admin;
        this.messages = messages;
        this.audit = audit;
    }

    /**
     * Lists tenants.
     *
     * @return tenant names
     * @throws PulsarAdminException if Pulsar rejects the request
     */
    @Operation(summary = "List tenants")
    @GetMapping("/tenants")
    public List<String> tenants() throws PulsarAdminException {
        return admin.listTenants();
    }

    /**
     * Lists the namespaces of a tenant.
     *
     * @param tenant tenant name
     * @return namespace names
     * @throws PulsarAdminException if Pulsar rejects the request
     */
    @Operation(summary = "List namespaces of a tenant")
    @GetMapping("/namespaces/{tenant}")
    public List<String> namespaces(
            @PathVariable @PulsarName String tenant) throws PulsarAdminException {
        return admin.listNamespaces(tenant);
    }

    /**
     * Lists the topics of a namespace.
     *
     * @param tenant tenant name
     * @param namespace namespace name
     * @return topic names
     * @throws PulsarAdminException if Pulsar rejects the request
     */
    @Operation(summary = "List topics of a namespace")
    @GetMapping("/topics/{tenant}/{namespace}")
    public List<String> topics(
            @PathVariable @PulsarName String tenant,
            @PathVariable @PulsarName String namespace) throws PulsarAdminException {
        return admin.listTopics(tenant, namespace);
    }

    /**
     * Creates a non-partitioned persistent topic.
     *
     * @param tenant tenant name
     * @param namespace namespace name
     * @param topic topic name
     * @param request current request, for the audit log
     * @throws PulsarAdminException if Pulsar rejects the request
     */
    @Operation(summary = "Create a topic")
    @PostMapping(TOPIC_PATH)
    @ResponseStatus(HttpStatus.CREATED)
    public void createTopic(
            @PathVariable @PulsarName String tenant,
            @PathVariable @PulsarName String namespace,
            @PathVariable @PulsarName String topic,
            HttpServletRequest request) throws PulsarAdminException {
        String fullTopic = persistentTopic(tenant, namespace, topic);
        admin.createTopic(fullTopic);
        audit.record(ClientAddress.of(request), AuditAction.CREATE_TOPIC, fullTopic, null);
    }

    /**
     * Deletes a topic, including its subscriptions and messages.
     *
     * @param tenant tenant name
     * @param namespace namespace name
     * @param topic topic name
     * @param request current request, for the audit log
     * @throws PulsarAdminException if Pulsar rejects the request
     */
    @Operation(summary = "Delete a topic")
    @DeleteMapping(TOPIC_PATH)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTopic(
            @PathVariable @PulsarName String tenant,
            @PathVariable @PulsarName String namespace,
            @PathVariable @PulsarName String topic,
            HttpServletRequest request) throws PulsarAdminException {
        String fullTopic = persistentTopic(tenant, namespace, topic);
        admin.deleteTopic(fullTopic);
        audit.record(ClientAddress.of(request), AuditAction.DELETE_TOPIC, fullTopic, null);
    }

    /**
     * Returns topic statistics.
     *
     * @param tenant tenant name
     * @param namespace namespace name
     * @param topic topic name
     * @return the statistics
     * @throws PulsarAdminException if Pulsar rejects the request
     */
    @Operation(summary = "Topic statistics")
    @GetMapping(TOPIC_PATH + "/stats")
    public TopicStats stats(
            @PathVariable @PulsarName String tenant,
            @PathVariable @PulsarName String namespace,
            @PathVariable @PulsarName String topic) throws PulsarAdminException {
        return admin.getTopicStats(persistentTopic(tenant, namespace, topic));
    }

    /**
     * Lists the subscriptions of a topic.
     *
     * @param tenant tenant name
     * @param namespace namespace name
     * @param topic topic name
     * @return subscription names
     * @throws PulsarAdminException if Pulsar rejects the request
     */
    @Operation(summary = "List subscriptions")
    @GetMapping(TOPIC_PATH + "/subscriptions")
    public List<String> subscriptions(
            @PathVariable @PulsarName String tenant,
            @PathVariable @PulsarName String namespace,
            @PathVariable @PulsarName String topic) throws PulsarAdminException {
        return admin.listSubscriptions(persistentTopic(tenant, namespace, topic));
    }

    /**
     * Creates a subscription at the earliest message.
     *
     * @param tenant tenant name
     * @param namespace namespace name
     * @param topic topic name
     * @param subscription subscription name
     * @param request current request, for the audit log
     * @throws PulsarAdminException if Pulsar rejects the request
     */
    @Operation(summary = "Create a subscription")
    @PostMapping(SUBSCRIPTION_PATH)
    @ResponseStatus(HttpStatus.CREATED)
    public void createSubscription(
            @PathVariable @PulsarName String tenant,
            @PathVariable @PulsarName String namespace,
            @PathVariable @PulsarName String topic,
            @PathVariable @PulsarName String subscription,
            HttpServletRequest request) throws PulsarAdminException {
        String fullTopic = persistentTopic(tenant, namespace, topic);
        admin.createSubscription(fullTopic, subscription);
        audit.record(ClientAddress.of(request), AuditAction.CREATE_SUBSCRIPTION, fullTopic,
                Map.of("subscription", subscription));
    }

    /**
     * Deletes a subscription.
     *
     * @param tenant tenant name
     * @param namespace namespace name
     * @param topic topic name
     * @param subscription subscription name
     * @param request current request, for the audit log
     * @throws PulsarAdminException if Pulsar rejects the request
     */
    @Operation(summary = "Delete a subscription")
    @DeleteMapping(SUBSCRIPTION_PATH)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSubscription(
            @PathVariable @PulsarName String tenant,
            @PathVariable @PulsarName String namespace,
            @PathVariable @PulsarName String topic,
            @PathVariable @PulsarName String subscription,
            HttpServletRequest request) throws PulsarAdminException {
        String fullTopic = persistentTopic(tenant, namespace, topic);
        admin.deleteSubscription(fullTopic, subscription);
        audit.record(ClientAddress.of(request), AuditAction.DELETE_SUBSCRIPTION, fullTopic,
                Map.of("subscription", subscription));
    }

    /**
     * Produces one message.
     *
     * @param tenant tenant name
     * @param namespace namespace name
     * @param topic topic name
     * @param body message to send
     * @param request current request, for the audit log
     * @return the id assigned by the broker
     * @throws PulsarClientException if the message cannot be sent
     */
    @Operation(summary = "Produce a message")
    @PostMapping(TOPIC_PATH + "/produce")
    public ProduceResponse produce(
            @PathVariable @PulsarName String tenant,
            @PathVariable @PulsarName String namespace,
            @PathVariable @PulsarName String topic,
            @Valid @RequestBody ProduceRequest body,
            HttpServletRequest request) throws PulsarClientException {
        String fullTopic = persistentTopic(tenant, namespace, topic);
        String messageId = messages.produce(fullTopic, body.payload(), body.key(), body.properties());
        Map<String, Object> details = new HashMap<>();
        details.put("messageId", messageId);
        if (body.properties() != null && !body.properties().isEmpty()) {
            details.put("properties", body.properties());
        }
        audit.recordMessage(ClientAddress.of(request), AuditAction.PRODUCE, fullTopic, body.payload(), body.key(),
                details);
        return new ProduceResponse(messageId);
    }

    /**
     * Returns the first messages of a subscription's backlog without consuming them.
     *
     * @param tenant tenant name
     * @param namespace namespace name
     * @param topic topic name
     * @param subscription subscription name
     * @param count number of messages (1–100)
     * @param request current request, for the audit log
     * @return the messages, oldest first
     * @throws PulsarAdminException if Pulsar rejects the request
     */
    @Operation(summary = "Peek backlog messages")
    @GetMapping(TOPIC_PATH + "/peek/{subscription}")
    public List<MessageDTO> peek(
            @PathVariable @PulsarName String tenant,
            @PathVariable @PulsarName String namespace,
            @PathVariable @PulsarName String topic,
            @PathVariable @PulsarName String subscription,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int count,
            HttpServletRequest request) throws PulsarAdminException {
        String fullTopic = persistentTopic(tenant, namespace, topic);
        List<MessageDTO> peeked = messages.peek(fullTopic, subscription, count);
        audit.record(ClientAddress.of(request), AuditAction.PEEK_MESSAGES, fullTopic,
                Map.of("subscription", subscription, "count", count));
        return peeked;
    }

    /**
     * Skips messages on a subscription: they are acknowledged without being delivered.
     *
     * @param tenant tenant name
     * @param namespace namespace name
     * @param topic topic name
     * @param subscription subscription name
     * @param count number of messages to skip (1–10,000)
     * @param request current request, for the audit log
     * @throws PulsarAdminException if Pulsar rejects the request
     */
    @Operation(summary = "Skip messages on a subscription")
    @PostMapping(SUBSCRIPTION_PATH + "/skip/{count}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void skip(
            @PathVariable @PulsarName String tenant,
            @PathVariable @PulsarName String namespace,
            @PathVariable @PulsarName String topic,
            @PathVariable @PulsarName String subscription,
            @PathVariable @Min(1) @Max(10_000) long count,
            HttpServletRequest request) throws PulsarAdminException {
        String fullTopic = persistentTopic(tenant, namespace, topic);
        messages.skip(fullTopic, subscription, count);
        audit.record(ClientAddress.of(request), AuditAction.SKIP_MESSAGES, fullTopic,
                Map.of("subscription", subscription, "count", count));
    }
}
