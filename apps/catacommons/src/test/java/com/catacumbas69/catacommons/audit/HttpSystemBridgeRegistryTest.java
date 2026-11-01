package com.catacumbas69.catacommons.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HttpSystemBridgeRegistryTest {

    private HttpServer server;
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> app = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private volatile int status = 200;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            method.set(exchange.getRequestMethod());
            path.set(exchange.getRequestURI().getPath());
            app.set(exchange.getRequestHeaders().getFirst(HttpSystemBridgeRegistry.APP_HEADER));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void register_putsTheBridgeByName_withTheAppAsActor() {
        HttpSystemBridgeRegistry registry = new HttpSystemBridgeRegistry(this::url, "catametricas",
                Duration.ofSeconds(2));

        registry.register(SystemBridge.audit("catametricas"));

        assertThat(method.get()).isEqualTo("PUT");
        assertThat(path.get()).isEqualTo("/api/v1/bridges/system/audit-catametricas");
        assertThat(app.get()).isEqualTo("catametricas");
        assertThat(body.get()).contains("\"name\":\"audit-catametricas\"",
                "\"topic\":\"persistent://catacumbas69/catametricas/events\"",
                "\"topic\":\"persistent://catacumbas69/cataudit/audit\"", "\"subscriptionType\":\"Failover\"");
    }

    @Test
    void register_throws_whenCataponteRefuses() {
        status = 400;
        HttpSystemBridgeRegistry registry = new HttpSystemBridgeRegistry(this::url, "catametricas",
                Duration.ofSeconds(2));

        assertThatThrownBy(() -> registry.register(SystemBridge.audit("catametricas")))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void body_isAnOrderedBridgeWithOneDestination() {
        Map<String, Object> request = HttpSystemBridgeRegistry.body(SystemBridge.audit("cataponte"));

        assertThat(request).containsEntry("name", "audit-cataponte").containsEntry("ordered", true)
                .containsEntry("enabled", true)
                .containsEntry("destinations", List.of(Map.of("topic", "persistent://catacumbas69/cataudit/audit")));
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
