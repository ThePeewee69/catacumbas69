package com.catacumbas69.cataudit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.catacumbas69.catacommons.consumers.DeadLetters;
import com.catacumbas69.catacommons.events.CloudEvent;
import com.catacumbas69.catacommons.events.EventData;
import com.catacumbas69.catacommons.events.EventPublisher;
import com.catacumbas69.catacommons.pulsar.CatacumbasTopics;
import com.catacumbas69.catacommons.test.CatacumbasContainers;
import com.catacumbas69.cataudit.dto.AuditEventResponse;
import com.catacumbas69.cataudit.dto.AuditPageResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.Reader;
import org.apache.pulsar.client.api.Schema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Cataudit stores what arrives on {@code catacumbas69/cataudit/audit} and serves it (TEST-3, orch.md decision
 * 91): an event is stored once however often it is delivered, and a message that is not a CloudEvent goes to the
 * DLQ. Runs the whole app against the containers of {@link CatacumbasContainers}; the test plays the
 * {@code audit-<app>} bridges by producing straight to the audit topic.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuditTrailIT {

    private static final Duration WAIT = Duration.ofSeconds(60);

    private static final String AUDIT = CatacumbasTopics.audit();

    private static final String SUBSCRIPTION = "cataudit";

    /** Builds the events as an app does; nothing is sent through it. */
    private static final EventPublisher EVENTS = new EventPublisher("catapulsar", null, Clock.systemUTC());

    private static PulsarClient client;
    private static PulsarAdmin admin;
    private static Producer<byte[]> producer;

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MeterRegistry meters;

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        CatacumbasContainers.register(registry, "cataudit");
    }

    @BeforeAll
    static void clients() throws Exception {
        client = CatacumbasContainers.pulsarClient();
        admin = CatacumbasContainers.pulsarAdmin();
    }

    @AfterAll
    static void closeClients() throws Exception {
        if (producer != null) {
            producer.close();
        }
        admin.close();
        client.close();
    }

    @BeforeEach
    void subscribed() throws Exception {
        // Cataudit's subscription starts at the latest message the first time: wait for it, so nothing is skipped
        await().atMost(WAIT).ignoreExceptions()
                .until(() -> admin.topics().getSubscriptions(AUDIT).contains(SUBSCRIPTION));
        if (producer == null) {
            producer = client.newProducer(Schema.BYTES).topic(AUDIT).create();
        }
    }

    @Test
    void event_isStored_andServedById() throws Exception {
        CloudEvent event = event("create");

        producer.send(json(event));

        AuditEventResponse stored = await().atMost(WAIT).pollInterval(Duration.ofMillis(500))
                .until(() -> find(event.id()), Objects::nonNull);
        assertThat(stored.app()).isEqualTo("catapulsar");
        assertThat(stored.entity()).isEqualTo("topic");
        assertThat(stored.action()).isEqualTo("create");
        assertThat(stored.type()).isEqualTo(event.type());
        assertThat(stored.subject()).isEqualTo(event.subject());
        assertThat(stored.actor()).isEqualTo("integration-test");
        assertThat(stored.details()).containsEntry("partitions", "3");
    }

    @Test
    void sameEvent_deliveredTwice_isStoredOnce() throws Exception {
        CloudEvent event = event("update");
        double duplicatesBefore = duplicates();

        producer.send(json(event));
        producer.send(json(event));

        await().atMost(WAIT).pollInterval(Duration.ofMillis(500)).until(() -> duplicates() > duplicatesBefore);
        AuditPageResponse page = http.getForObject("/api/v1/audit?subject={subject}", AuditPageResponse.class,
                event.subject());
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items()).extracting(AuditEventResponse::id).containsExactly(event.id());
    }

    @Test
    void messageThatIsNotACloudEvent_goesToTheDlq() throws Exception {
        byte[] payload = ("not a CloudEvent " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);

        producer.send(payload);

        Message<byte[]> dead = findOnDlq(payload);
        assertThat(dead).as("the message on %s", DeadLetters.topicOf(AUDIT, SUBSCRIPTION)).isNotNull();
        assertThat(dead.getProperty(DeadLetters.REAL_TOPIC)).isEqualTo(AUDIT);
        assertThat(dead.getProperty(DeadLetters.REASON)).isEqualTo("not a CloudEvent with an id");
    }

    /** An audit event of a subject of its own, so tests do not see each other's entries. */
    private static CloudEvent event(String action) {
        String subject = "persistent://public/default/it-audit-" + UUID.randomUUID().toString().substring(0, 8);
        return EVENTS.publish("topic", action, subject,
                EventData.action("integration-test", Map.of("partitions", "3")));
    }

    private byte[] json(CloudEvent event) throws Exception {
        return objectMapper.writeValueAsBytes(event);
    }

    /** The stored entry, or {@code null} while Cataudit answers 404. */
    private AuditEventResponse find(String id) {
        ResponseEntity<AuditEventResponse> response = http.getForEntity("/api/v1/audit/{id}",
                AuditEventResponse.class, id);
        return response.getStatusCode().is2xxSuccessful() ? response.getBody() : null;
    }

    private double duplicates() {
        Counter counter = meters.find("cataudit.events").tag("outcome", "duplicate").counter();
        return counter == null ? 0 : counter.count();
    }

    /** Reads the DLQ from the start until the message with this payload, or {@code null} after {@link #WAIT}. */
    private static Message<byte[]> findOnDlq(byte[] payload) throws Exception {
        long deadline = System.nanoTime() + WAIT.toNanos();
        try (Reader<byte[]> reader = client.newReader(Schema.BYTES)
                .topic(DeadLetters.topicOf(AUDIT, SUBSCRIPTION))
                .startMessageId(MessageId.earliest)
                .create()) {
            while (System.nanoTime() < deadline) {
                Message<byte[]> message = reader.readNext(1, TimeUnit.SECONDS);
                if (message != null && Arrays.equals(message.getData(), payload)) {
                    return message;
                }
            }
        }
        return null;
    }
}
