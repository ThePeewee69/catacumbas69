package com.catacumbas69.catametricas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.catacumbas69.catacommons.test.CatacumbasContainers;
import com.catacumbas69.catametricas.dto.ForcedCollectionResponse;
import com.catacumbas69.catametricas.dto.SubscriptionStatsResponse;
import com.catacumbas69.catametricas.dto.TopicStatsResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.Schema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A forced collection ({@code POST /api/v1/collect}) reads a namespace from Pulsar and stores it in MongoDB, and
 * the subscription and topic APIs serve what it stored (TEST-3, orch.md decision 91). The test leaves a known
 * backlog on a subscription of its own and expects to find it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ForcedCollectionIT {

    private static final Duration WAIT = Duration.ofSeconds(60);

    private static final String TENANT = "public";
    private static final String NAMESPACE = "default";
    private static final int BACKLOG = 7;

    private static PulsarClient client;
    private static PulsarAdmin admin;

    @Autowired
    private TestRestTemplate http;

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        CatacumbasContainers.register(registry, "catametricas");
    }

    @BeforeAll
    static void clients() throws Exception {
        client = CatacumbasContainers.pulsarClient();
        admin = CatacumbasContainers.pulsarAdmin();
    }

    @AfterAll
    static void closeClients() throws Exception {
        admin.close();
        client.close();
    }

    @Test
    void forcedCollection_storesTheBacklogOfASubscription() throws Exception {
        String local = "it-metricas-" + UUID.randomUUID().toString().substring(0, 8);
        String topic = "persistent://" + TENANT + "/" + NAMESPACE + "/" + local;
        String subscription = "it-sub";
        admin.topics().createNonPartitionedTopic(topic);
        admin.topics().createSubscription(topic, subscription, MessageId.latest);
        try (Producer<byte[]> producer = client.newProducer(Schema.BYTES).topic(topic).create()) {
            for (int i = 0; i < BACKLOG; i++) {
                producer.send(("message " + i).getBytes(StandardCharsets.UTF_8));
            }
        }

        // Collected again on every poll: requests in the same 5 s window share one collection
        SubscriptionStatsResponse stored = await().atMost(WAIT).pollInterval(Duration.ofSeconds(2))
                .until(() -> {
                    collectNamespace();
                    return subscription(local, subscription);
                }, found -> found != null && found.backlog() == BACKLOG);
        assertThat(stored.topic()).isEqualTo(topic);
        assertThat(stored.tenant()).isEqualTo(TENANT);
        assertThat(stored.namespace()).isEqualTo(NAMESPACE);
        assertThat(stored.collectedAt()).isNotNull();

        TopicStatsResponse[] topics = http.getForObject("/api/v1/topics?tenant={tenant}&namespace={namespace}",
                TopicStatsResponse[].class, TENANT, NAMESPACE);
        assertThat(topics).isNotNull();
        TopicStatsResponse stats = Arrays.stream(topics)
                .filter(t -> topic.equals(t.topic()))
                .findFirst()
                .orElse(null);
        assertThat(stats).as("the topic in /api/v1/topics").isNotNull();
        assertThat(stats.current().backlogMsgs()).isEqualTo(BACKLOG);
        assertThat(stats.current().subscriptions()).isEqualTo(1);
    }

    private void collectNamespace() {
        ForcedCollectionResponse response = http.postForObject(
                "/api/v1/collect?tenant={tenant}&namespace={namespace}", null, ForcedCollectionResponse.class,
                TENANT, NAMESPACE);
        assertThat(response).isNotNull();
        assertThat(response.namespaces()).containsExactly(TENANT + "/" + NAMESPACE);
        assertThat(response.failed()).isEmpty();
    }

    /** The stored state of one subscription of the topic, or {@code null} while not collected. */
    private SubscriptionStatsResponse subscription(String localTopic, String subscription) {
        SubscriptionStatsResponse[] all = http.getForObject(
                "/api/v1/subscriptions?tenant={tenant}&namespace={namespace}&topic={topic}",
                SubscriptionStatsResponse[].class, TENANT, NAMESPACE, localTopic);
        return all == null ? null : Arrays.stream(all)
                .filter(Objects::nonNull)
                .filter(s -> subscription.equals(s.subscription()))
                .findFirst()
                .orElse(null);
    }
}
