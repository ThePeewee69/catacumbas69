package com.catacumbas69.catavigia.checks;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Reads the subscriptions from Catametricas ({@code GET /api/v1/subscriptions}, orch.md decision 77) and sums the
 * partitions of each topic.
 */
@Component
public class SubscriptionsReader {

    private final AppsClient apps;
    private final AppUrls urls;

    /**
     * Creates the reader.
     *
     * @param apps HTTP client
     * @param urls where Catametricas answers
     */
    public SubscriptionsReader(AppsClient apps, AppUrls urls) {
        this.apps = apps;
        this.urls = urls;
    }

    /**
     * Returns every subscription of every active topic, partitions summed.
     *
     * @return one entry per topic (without partition suffix) and subscription, in Catametricas' order
     * @throws SourceUnavailableException if Catametricas does not answer
     */
    public List<SubscriptionBacklog> read() throws SourceUnavailableException {
        JsonNode rows = apps.get(urls.catametricas() + "/api/v1/subscriptions");
        Map<String, SubscriptionBacklog> summed = new LinkedHashMap<>();
        for (JsonNode row : rows) {
            String topic = logicalTopic(row.path("topic").asText(), row.path("baseName").asText());
            String subscription = row.path("subscription").asText();
            long backlog = row.path("backlog").asLong();
            summed.merge(topic + "|" + subscription,
                    new SubscriptionBacklog(topic, row.path("baseName").asText(), subscription, backlog),
                    (a, b) -> new SubscriptionBacklog(a.topic(), a.baseName(), a.subscription(),
                            a.backlog() + b.backlog()));
        }
        return new ArrayList<>(summed.values());
    }

    /** A partition's topic: {@code t/ns/orders-partition-1} with base name {@code orders} is {@code t/ns/orders}. */
    static String logicalTopic(String topic, String baseName) {
        int slash = topic.lastIndexOf('/');
        if (slash < 0 || baseName.isEmpty()) {
            return topic;
        }
        return topic.substring(0, slash + 1) + baseName;
    }

    /**
     * One subscription of a topic, partitions summed.
     *
     * @param topic topic without partition suffix, with its domain
     * @param baseName local name without partition suffix
     * @param subscription subscription name
     * @param backlog messages waiting, over every partition
     */
    public record SubscriptionBacklog(String topic, String baseName, String subscription, long backlog) {
    }
}
