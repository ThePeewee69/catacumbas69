package com.catacumbas69.cataponte.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.catacumbas69.cataponte.TestBridges;
import com.catacumbas69.cataponte.model.SubscriptionCleanupDocument;
import com.catacumbas69.cataponte.repository.BridgeRepository;
import com.catacumbas69.cataponte.repository.SubscriptionCleanupRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.apache.pulsar.client.admin.Topics;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

class SubscriptionCleanupTest {

    private static final Instant NOW = Instant.parse("2026-11-04T22:00:00Z");
    private static final String TOPIC = TestBridges.SOURCE;
    private static final String SUB = "cataponte-1";

    private final SubscriptionCleanupRepository repository = mock(SubscriptionCleanupRepository.class);
    private final BridgeRepository bridges = mock(BridgeRepository.class);
    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);
    private final PulsarAdmin admin = mock(PulsarAdmin.class);
    private final Topics topics = mock(Topics.class);
    private final AuditLog audit = mock(AuditLog.class);
    private final SubscriptionCleanup cleanup = new SubscriptionCleanup(repository, bridges, mongoTemplate, admin,
            audit, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(30), Duration.ofMinutes(1));

    SubscriptionCleanupTest() {
        when(admin.topics()).thenReturn(topics);
    }

    @Test
    void schedule_queuesTheSubscription_afterTheDelay() {
        cleanup.schedule(TestBridges.unordered("1", TestBridges.DEST_A), "10.0.0.1");

        ArgumentCaptor<SubscriptionCleanupDocument> saved = ArgumentCaptor.forClass(SubscriptionCleanupDocument.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getId()).isEqualTo(TOPIC + "|" + SUB);
        assertThat(saved.getValue().getNotBefore()).isEqualTo(NOW.plusSeconds(30));
        assertThat(saved.getValue().getActor()).isEqualTo("10.0.0.1");
        assertThat(saved.getValue().getBridgeName()).isEqualTo("bridge-1");
    }

    @Test
    void run_deletesTheSubscription_andPublishesIt() throws Exception {
        SubscriptionCleanupDocument pending = pending();

        cleanup.run(pending);

        verify(topics).deleteSubscription(TOPIC, SUB);
        verify(repository).deleteById(pending.getId());
        verify(audit).subscriptionDeleted(eq(pending), any());
    }

    @Test
    void run_keepsTheSubscription_whenABridgeUsesItAgain() throws Exception {
        when(bridges.findBySourceTopicAndSourceSubscription(TOPIC, SUB))
                .thenReturn(List.of(TestBridges.unordered("2", TestBridges.DEST_A)));
        SubscriptionCleanupDocument pending = pending();

        cleanup.run(pending);

        verify(topics, never()).deleteSubscription(any(), any());
        verify(repository).deleteById(pending.getId());
        verify(audit, never()).subscriptionDeleted(any(), any());
    }

    @Test
    void run_treatsAMissingSubscriptionAsDone() throws Exception {
        doThrow(mock(PulsarAdminException.NotFoundException.class)).when(topics).deleteSubscription(TOPIC, SUB);
        SubscriptionCleanupDocument pending = pending();

        cleanup.run(pending);

        verify(repository).deleteById(pending.getId());
        verify(audit, never()).subscriptionDeleted(any(), any());
    }

    @Test
    void run_keepsItQueued_whenPulsarRefuses() throws Exception {
        doThrow(mock(PulsarAdminException.PreconditionFailedException.class))
                .when(topics).deleteSubscription(TOPIC, SUB);
        SubscriptionCleanupDocument pending = pending();

        cleanup.run(pending);

        verify(repository, never()).deleteById(any());
        verify(mongoTemplate).updateFirst(any(Query.class), any(Update.class), eq(SubscriptionCleanupDocument.class));
        verify(audit, never()).subscriptionDeleted(any(), any());
    }

    private static SubscriptionCleanupDocument pending() {
        SubscriptionCleanupDocument pending = new SubscriptionCleanupDocument();
        pending.setId(SubscriptionCleanupDocument.idOf(TOPIC, SUB));
        pending.setTopic(TOPIC);
        pending.setSubscription(SUB);
        pending.setBridgeName("bridge-1");
        pending.setActor("10.0.0.1");
        return pending;
    }
}
