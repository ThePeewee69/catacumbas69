package com.catacumbas69.cataponte;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.catacumbas69.cataponte.dto.BridgeRequest;
import com.catacumbas69.cataponte.dto.BridgeResponse;
import com.catacumbas69.catacommons.test.CatacumbasContainers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.SubscriptionInitialPosition;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A bridge created through the API relays real messages from one Pulsar topic to another, with their keys and
 * properties (TEST-3, orch.md decision 91). Runs the whole app against the containers of
 * {@link CatacumbasContainers}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "cataponte.bridge.reconcile-interval=PT1S")
class BridgeForwardingIT {

    private static final int MESSAGES = 20;

    private static PulsarClient client;
    private static PulsarAdmin admin;

    @Autowired
    private TestRestTemplate http;

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        CatacumbasContainers.register(registry, "cataponte");
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
    void enabledBridge_relaysEveryMessage_withItsKeyAndProperties() throws Exception {
        String id = UUID.randomUUID().toString().substring(0, 8);
        String source = "persistent://public/default/it-source-" + id;
        String destination = "persistent://public/default/it-destination-" + id;
        String name = "it-bridge-" + id;

        try (Consumer<byte[]> received = client.newConsumer()
                .topic(destination)
                .subscriptionName("it-check")
                .subscriptionInitialPosition(SubscriptionInitialPosition.Earliest)
                .subscribe()) {

            BridgeRequest request = new BridgeRequest(name, "integration test", true,
                    new BridgeRequest.Source(source, name, null),
                    List.of(new BridgeRequest.Destination(destination)),
                    true, true, false, null, null, null);
            ResponseEntity<BridgeResponse> created =
                    http.postForEntity("/api/v1/bridges", request, BridgeResponse.class);
            assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(created.getBody()).isNotNull();
            assertThat(created.getBody().enabled()).isTrue();

            // The bridge starts within a reconcile interval; its subscription on the source shows it is running
            await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(500)).ignoreExceptions()
                    .until(() -> admin.topics().getSubscriptions(source).contains(name));

            try (Producer<byte[]> producer = client.newProducer().topic(source).create()) {
                for (int i = 0; i < MESSAGES; i++) {
                    producer.newMessage()
                            .key("key-" + i)
                            .property("n", Integer.toString(i))
                            .value(("message-" + i).getBytes(StandardCharsets.UTF_8))
                            .send();
                }
            }

            Map<String, Message<byte[]>> byValue = new HashMap<>();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (byValue.size() < MESSAGES && System.nanoTime() < deadline) {
                Message<byte[]> message = received.receive(5, TimeUnit.SECONDS);
                if (message != null) {
                    byValue.put(new String(message.getValue(), StandardCharsets.UTF_8), message);
                    received.acknowledge(message);
                }
            }

            assertThat(byValue).hasSize(MESSAGES);
            for (int i = 0; i < MESSAGES; i++) {
                Message<byte[]> message = byValue.get("message-" + i);
                assertThat(message).as("message-%d", i).isNotNull();
                assertThat(message.getKey()).isEqualTo("key-" + i);
                assertThat(message.getProperty("n")).isEqualTo(Integer.toString(i));
            }
        }
    }
}
