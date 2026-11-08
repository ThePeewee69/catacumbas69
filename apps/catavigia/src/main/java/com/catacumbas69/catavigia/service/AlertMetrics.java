package com.catacumbas69.catavigia.service;

import com.catacumbas69.catavigia.model.Alert;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * {@code catavigia_alerts_open{type,severity}} on {@code /actuator/prometheus} (orch.md decision 49), refreshed once
 * per round: the open alerts in MongoDB plus this replica's {@code mongodb.down}. While MongoDB is down the
 * stored alerts keep their last count.
 */
@Component
public class AlertMetrics {

    private static final Logger LOG = LoggerFactory.getLogger(AlertMetrics.class);

    private final MongoTemplate mongoTemplate;
    private final MultiGauge open;
    private Map<List<String>, Integer> stored = Map.of();

    /**
     * Creates the metric.
     *
     * @param mongoTemplate template, to count the open alerts
     * @param registry meter registry
     */
    public AlertMetrics(MongoTemplate mongoTemplate, MeterRegistry registry) {
        this.mongoTemplate = mongoTemplate;
        this.open = MultiGauge.builder("catavigia.alerts.open")
                .description("Open alerts by type and severity")
                .register(registry);
    }

    /**
     * Counts the open alerts again.
     *
     * @param local this replica's {@code mongodb.down}, or {@code null}
     */
    public synchronized void refresh(Alert local) {
        try {
            Map<List<String>, Integer> counts = new TreeMap<>((a, b) -> String.join("|", a).compareTo(
                    String.join("|", b)));
            mongoTemplate.findAll(Alert.class).forEach(alert ->
                    counts.merge(List.of(alert.type(), alert.severity()), 1, Integer::sum));
            stored = counts;
        } catch (RuntimeException e) {
            LOG.debug("Open alerts not counted, keeping the last count: {}", e.getMessage());
        }
        Map<List<String>, Integer> all = new TreeMap<>((a, b) -> String.join("|", a).compareTo(String.join("|", b)));
        all.putAll(stored);
        if (local != null) {
            all.merge(List.of(local.type(), local.severity()), 1, Integer::sum);
        }
        List<MultiGauge.Row<?>> rows = new ArrayList<>();
        all.forEach((tags, count) -> rows.add(MultiGauge.Row.of(Tags.of("type", tags.get(0), "severity",
                tags.get(1)), count)));
        open.register(rows, true);
    }
}
