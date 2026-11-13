package com.catacumbas69.catapulsar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.catacumbas69.catacommons.pulsar.CatacumbasTopics;
import com.catacumbas69.catacommons.settings.SettingValue;
import com.catacumbas69.catacommons.test.CatacumbasContainers;
import com.catacumbas69.catapulsar.dto.AlertThresholdResponse;
import com.catacumbas69.catapulsar.dto.CountSettingRequest;
import com.catacumbas69.catapulsar.dto.MessageDTO;
import com.catacumbas69.catapulsar.dto.ProduceRequest;
import com.catacumbas69.catapulsar.dto.ProduceResponse;
import com.catacumbas69.catapulsar.model.Setting;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.Reader;
import org.apache.pulsar.client.api.Schema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The UI's API against a real Pulsar and MongoDB (TEST-3, orch.md decision 91): a topic and a subscription
 * created through it, a message produced and peeked with its key and properties, a backlog cleared, and an alert
 * threshold saved in MongoDB and published on the settings topic that the other apps read.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CatapulsarApiIT {

    private static final Duration WAIT = Duration.ofSeconds(60);

    private static final String TENANT = "public";
    private static final String NAMESPACE = "default";

    private static PulsarClient client;
    private static PulsarAdmin admin;

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private MongoTemplate mongoTemplate;

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        CatacumbasContainers.register(registry, "catapulsar");
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
    void topicAndSubscription_createdThroughTheApi_produceAndPeekAMessage() {
        String topic = newTopicWithSubscription("it-sub");

        ResponseEntity<ProduceResponse> produced = http.postForEntity(topicPath(topic) + "/produce",
                new ProduceRequest("{\"hello\":\"catacumbas\"}", "key-1", Map.of("origin", "integration-test")),
                ProduceResponse.class);
        assertThat(produced.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(produced.getBody()).isNotNull();

        MessageDTO[] peeked = http.getForObject(topicPath(topic) + "/peek/it-sub?count=10", MessageDTO[].class);
        assertThat(peeked).hasSize(1);
        MessageDTO message = peeked[0];
        assertThat(message.payload()).isEqualTo("{\"hello\":\"catacumbas\"}");
        assertThat(message.key()).isEqualTo("key-1");
        assertThat(message.properties()).containsEntry("origin", "integration-test");
        assertThat(message.binary()).isFalse();
    }

    @Test
    void clearBacklog_leavesTheSubscriptionWithNothingToRead() throws Exception {
        String topic = newTopicWithSubscription("it-sub");
        for (int i = 0; i < 5; i++) {
            http.postForEntity(topicPath(topic) + "/produce", new ProduceRequest("message " + i, null, null),
                    ProduceResponse.class);
        }
        await().atMost(WAIT).ignoreExceptions().until(() -> backlog(topic, "it-sub") == 5);

        ResponseEntity<Void> cleared = http.postForEntity(topicPath(topic) + "/subscriptions/it-sub/clear-backlog",
                null, Void.class);

        assertThat(cleared.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        await().atMost(WAIT).ignoreExceptions().until(() -> backlog(topic, "it-sub") == 0);
    }

    @Test
    void alertThreshold_isSavedInMongoDb_andPublishedOnTheSettingsTopic() throws Exception {
        ResponseEntity<AlertThresholdResponse> changed = http.exchange("/api/v1/settings/alerts/debounce",
                HttpMethod.PUT, new HttpEntity<>(new CountSettingRequest(3)), AlertThresholdResponse.class);

        assertThat(changed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(changed.getBody()).isNotNull();
        assertThat(changed.getBody().value()).isEqualTo(3);
        assertThat(changed.getBody().custom()).isTrue();
        Setting saved = mongoTemplate.findById("alerts.debounce", Setting.class);
        assertThat(saved).isNotNull();
        assertThat(saved.getValue()).isEqualTo("3");
        await().atMost(WAIT).ignoreExceptions().until(() -> "3".equals(lastPublished("alerts.debounce")));
    }

    /** Creates a topic of its own and a subscription on it, both through the API. */
    private String newTopicWithSubscription(String subscription) {
        String topic = "it-catapulsar-" + UUID.randomUUID().toString().substring(0, 8);
        assertThat(http.postForEntity(topicPath(topic), null, Void.class).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(http.postForEntity(topicPath(topic) + "/subscriptions/" + subscription, null, Void.class)
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return topic;
    }

    private static String topicPath(String topic) {
        return "/api/v1/topics/" + TENANT + "/" + NAMESPACE + "/" + topic;
    }

    private static long backlog(String topic, String subscription) throws Exception {
        return admin.topics().getStats("persistent://" + TENANT + "/" + NAMESPACE + "/" + topic)
                .getSubscriptions().get(subscription).getMsgBacklog();
    }

    /** The last value of a key on the settings topic, read from the start; {@code null} if none or removed. */
    private static String lastPublished(String key) throws Exception {
        String last = null;
        try (Reader<SettingValue> reader = client.newReader(Schema.JSON(SettingValue.class))
                .topic(CatacumbasTopics.settings())
                .startMessageId(MessageId.earliest)
                .create()) {
            while (reader.hasMessageAvailable()) {
                Message<SettingValue> message = reader.readNext(5, TimeUnit.SECONDS);
                if (message == null) {
                    break;
                }
                if (key.equals(message.getKey())) {
                    last = message.getData().length == 0 || message.getValue() == null
                            ? null : message.getValue().value();
                }
            }
        }
        return last;
    }
}
