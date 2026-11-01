package com.catacumbas69.catacommons.audit;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Registers system bridges through Cataponte's API: {@code PUT /api/v1/bridges/system/{name}}, which creates
 * or updates the bridge by name (ADR 0009). The app's name is sent in {@value #APP_HEADER} so Cataponte
 * records it as the actor.
 */
public class HttpSystemBridgeRegistry implements SystemBridgeRegistry {

    /** Header that names the calling app. */
    public static final String APP_HEADER = "X-Catacumbas69-App";

    private final RestClient client;
    private final CataponteLocation location;
    private final String app;

    /**
     * Creates the registry.
     *
     * @param location Cataponte's base URL
     * @param app the calling app's name
     * @param timeout connect and read timeout
     */
    public HttpSystemBridgeRegistry(CataponteLocation location, String app, Duration timeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        this.client = RestClient.builder().requestFactory(factory).build();
        this.location = location;
        this.app = app;
    }

    @Override
    public void register(SystemBridge bridge) {
        client.put()
                .uri(location.baseUrl() + "/api/v1/bridges/system/{name}", bridge.name())
                .contentType(MediaType.APPLICATION_JSON)
                .header(APP_HEADER, app)
                .body(body(bridge))
                .retrieve()
                .toBodilessEntity();
    }

    /**
     * Builds the request body: Cataponte's bridge definition, ordered, with a {@code Failover} subscription so
     * several Cataponte replicas can share it later (ADR 0012).
     *
     * @param bridge the bridge
     * @return the JSON body as a map
     */
    static Map<String, Object> body(SystemBridge bridge) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("topic", bridge.sourceTopic());
        source.put("subscription", bridge.subscription());
        source.put("subscriptionType", "Failover");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", bridge.name());
        body.put("description", bridge.description());
        body.put("enabled", true);
        body.put("source", source);
        body.put("destinations", List.of(Map.of("topic", bridge.destinationTopic())));
        body.put("ordered", true);
        return body;
    }
}
