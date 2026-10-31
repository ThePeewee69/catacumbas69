package com.catacumbas69.catametricas.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.catacumbas69.catametricas.TestProperties;
import java.util.List;
import java.util.Map;
import org.apache.pulsar.client.admin.Namespaces;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.apache.pulsar.client.admin.Tenants;
import org.apache.pulsar.client.admin.Topics;
import org.apache.pulsar.common.policies.data.SubscriptionStats;
import org.apache.pulsar.common.policies.data.TopicStats;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class PulsarStatsReaderTest {

    private static final String ORDERS = "persistent://home/media/orders";
    private static final String BROKEN = "persistent://home/media/broken";
    private static final String SYSTEM = "persistent://home/media/__change_events";

    private final PulsarAdmin admin = mock(PulsarAdmin.class);
    private final Tenants tenants = mock(Tenants.class);
    private final Namespaces namespaces = mock(Namespaces.class);
    private final Topics topics = mock(Topics.class);
    private final PulsarStatsReader reader = new PulsarStatsReader(admin, TestProperties.properties());

    @BeforeEach
    void wire() throws PulsarAdminException {
        when(admin.tenants()).thenReturn(tenants);
        when(admin.namespaces()).thenReturn(namespaces);
        when(admin.topics()).thenReturn(topics);
        when(tenants.getTenants()).thenReturn(List.of("home"));
        when(namespaces.getNamespaces("home")).thenReturn(List.of("home/media", "home/locked"));
        when(topics.getList("home/media")).thenReturn(List.of(ORDERS, BROKEN, SYSTEM));
        when(topics.getList("home/locked")).thenThrow(mock(PulsarAdminException.NotAuthorizedException.class));
    }

    @Test
    void readsEveryTopic_skipsSystemTopics_andReportsFailures() throws Exception {
        TopicStats stats = stats();
        when(topics.getStats(ORDERS)).thenReturn(stats);
        when(topics.getStats(BROKEN)).thenThrow(mock(PulsarAdminException.class));

        PulsarReading reading = reader.read();

        assertThat(reading.samples()).singleElement().satisfies(sample -> {
            assertThat(sample.topic()).isEqualTo(ORDERS);
            assertThat(sample.msgInCounter()).isEqualTo(500);
            assertThat(sample.backlogMsgs()).isEqualTo(12);
            assertThat(sample.consumers()).isEqualTo(2);
        });
        assertThat(reading.failedTopics()).containsExactly(BROKEN);
        assertThat(reading.listedNamespaces()).containsExactly("home/media");
        verify(topics, never()).getStats(SYSTEM);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static TopicStats stats() {
        SubscriptionStats sub = mock(SubscriptionStats.class);
        when(sub.getMsgBacklog()).thenReturn(12L);
        when(sub.getConsumers()).thenReturn((List) List.of(Mockito.mock(Object.class), Mockito.mock(Object.class)));
        TopicStats stats = mock(TopicStats.class);
        when(stats.getMsgInCounter()).thenReturn(500L);
        when(stats.getMsgOutCounter()).thenReturn(400L);
        when(stats.getSubscriptions()).thenReturn((Map) Map.of("s1", sub));
        when(stats.getPublishers()).thenReturn((List) List.of());
        return stats;
    }

    @Test
    void doesNotCallStats_whenNoTopicIsListed() throws Exception {
        when(topics.getList("home/media")).thenReturn(List.of());

        assertThat(reader.read().samples()).isEmpty();
        verify(topics, never()).getStats(anyString());
    }
}
