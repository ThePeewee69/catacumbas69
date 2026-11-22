package com.catacumbas69.catasonarr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.catacumbas69.catacommons.test.CatacumbasContainers;
import com.catacumbas69.catasonarr.dto.SystemStatusResponse;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Against real Pulsar and MongoDB (TEST-3, orch.md decision 91): Catasonarr creates its namespace and its one events
 * topic at start-up (ADR 0008), becomes ready, and answers Sonarr's connection test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProvisioningIT {

    private static final Duration WAIT = Duration.ofSeconds(60);

    @Autowired
    private TestRestTemplate http;

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        CatacumbasContainers.register(registry, "catasonarr");
    }

    @Test
    void startUp_createsTheEventsTopic_andAnswersTheConnectionTest() throws Exception {
        try (PulsarAdmin admin = CatacumbasContainers.pulsarAdmin()) {
            await().atMost(WAIT).untilAsserted(() -> assertThat(admin.topics().getList("catacumbas69/catasonarr"))
                    .contains("persistent://catacumbas69/catasonarr/events"));
        }
        await().atMost(WAIT).untilAsserted(() -> assertThat(
                http.getForObject("/actuator/health/readiness", JsonNode.class).path("status").asText())
                .isEqualTo("UP"));

        SystemStatusResponse status = http.getForObject("/api/v3/system/status", SystemStatusResponse.class);
        assertThat(status.appName()).isEqualTo("Catasonarr");
        assertThat(status.version()).isEqualTo("4.0.20.0");
    }
}
