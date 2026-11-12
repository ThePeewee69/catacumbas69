package com.catacumbas69.catavigia;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.catacumbas69.catacommons.alerts.AlertSetting;
import com.catacumbas69.catacommons.test.CatacumbasContainers;
import com.catacumbas69.catavigia.dto.AlertResponse;
import com.catacumbas69.catavigia.model.AlertTypes;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * An app whose readiness is not {@code UP} opens a {@code service.down} alert, and the alert closes and moves to
 * the archive when the app is ready again (TEST-3, orch.md decision 91). The whole round runs for real: the
 * scheduler publishes the check tasks on Pulsar, the consumer runs them, the alert engine keeps the alerts in
 * MongoDB. The watched apps are one fake, a JDK {@link HttpServer} whose readiness the test switches.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "catavigia.checks.initial-delay=PT5S")
class ServiceDownAlertIT {

    /** Rounds of 10 s, so a few rounds plus Pulsar's and MongoDB's own pace. */
    private static final Duration WAIT = Duration.ofSeconds(90);

    /** The app the test looks at; the other three point at the same fake and open alerts of their own. */
    private static final String SUBJECT = "cataponte";

    /** What the fake app's readiness answers: {@code DOWN} until the test brings it back. */
    private static final AtomicReference<String> READINESS = new AtomicReference<>("DOWN");

    /** Started when the class loads, so its URL is there whenever Spring reads the properties. */
    private static final HttpServer FAKE_APP = startFakeApp();

    @Autowired
    private TestRestTemplate http;

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        CatacumbasContainers.register(registry, "catavigia");
        String url = "http://" + FAKE_APP.getAddress().getHostString() + ":" + FAKE_APP.getAddress().getPort();
        for (String app : List.of("catapulsar", "cataponte", "catametricas", "cataudit")) {
            registry.add("catavigia.apps." + app + "-url", () -> url);
        }
    }

    @BeforeAll
    static void shortRounds() throws Exception {
        // The shortest rounds and a debounce of one round, on the settings topic before Catavigia starts
        CatacumbasContainers.setting(AlertSetting.CHECK_INTERVAL.key(),
                Long.toString(AlertSetting.CHECK_INTERVAL.min()));
        CatacumbasContainers.setting(AlertSetting.DEBOUNCE.key(), Long.toString(AlertSetting.DEBOUNCE.min()));
    }

    @AfterAll
    static void stopFakeApp() {
        FAKE_APP.stop(0);
    }

    @Test
    void appNotReady_opensServiceDown_whichClosesWhenTheAppIsReadyAgain() {
        READINESS.set("DOWN");

        AlertResponse opened = await().atMost(WAIT).pollInterval(Duration.ofSeconds(1))
                .until(() -> openServiceDown(SUBJECT), Objects::nonNull);
        assertThat(opened.severity()).isEqualTo(AlertTypes.CRITICAL);
        assertThat(opened.details())
                .containsEntry("readiness", "DOWN")
                .containsEntry("components", "mongo=DOWN");
        assertThat(opened.closedAt()).isNull();

        READINESS.set("UP");

        await().atMost(WAIT).pollInterval(Duration.ofSeconds(1))
                .until(() -> openServiceDown(SUBJECT) == null);
        AlertResponse closed = http.getForObject("/api/v1/alerts/{id}", AlertResponse.class, opened.id());
        assertThat(closed.key()).isEqualTo(opened.key());
        assertThat(closed.openedAt()).isEqualTo(opened.openedAt());
        assertThat(closed.closedAt()).isAfter(opened.openedAt());
        assertThat(closed.durationSeconds()).isNotNull();
    }

    /** The open {@code service.down} alert of an app, or {@code null}. */
    private AlertResponse openServiceDown(String subject) {
        AlertResponse[] open = http.getForObject("/api/v1/alerts", AlertResponse[].class);
        return Arrays.stream(open == null ? new AlertResponse[0] : open)
                .filter(alert -> AlertTypes.SERVICE_DOWN.equals(alert.type()) && subject.equals(alert.subject()))
                .findFirst()
                .orElse(null);
    }

    /** The fake app: Actuator's health and readiness, answering {@link #READINESS}; anything else is a 404. */
    private static HttpServer startFakeApp() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/actuator/health", ServiceDownAlertIT::health);
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException("Fake app not started", e);
        }
    }

    private static void health(HttpExchange exchange) throws IOException {
        String status = READINESS.get();
        String body = exchange.getRequestURI().getPath().endsWith("/readiness")
                ? "{\"status\":\"" + status + "\"}"
                : "{\"status\":\"" + status + "\",\"components\":{\"mongo\":{\"status\":\"" + status + "\"}}}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        // Like Actuator: 503 while not UP, with the same JSON
        exchange.sendResponseHeaders("UP".equals(status) ? 200 : 503, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
