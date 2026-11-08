package com.catacumbas69.catavigia.checks;

import com.catacumbas69.catacommons.consumers.ConsumerGate;
import com.catacumbas69.catacommons.consumers.ConsumerStatus;
import com.catacumbas69.catavigia.config.CatavigiaProperties;
import com.catacumbas69.catavigia.model.AlertTypes;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * {@code consumer.paused}: a consumer of ours is paused, automatically or by hand, on at least one replica that
 * still reports. One alert per {@code <app>/<consumer>}; the details name the paused replicas (orch.md decision 79).
 */
@Component
public class ConsumersCheck implements Check {

    /** Check name. */
    public static final String NAME = "consumers";

    private final ConsumerStatusViews views;
    private final Duration staleAfter;
    private final Clock clock;

    /**
     * Creates the check.
     *
     * @param views the consumers' status
     * @param properties application properties, for when a replica is gone
     * @param clock time source
     */
    public ConsumersCheck(ConsumerStatusViews views, CatavigiaProperties properties, Clock clock) {
        this.views = views;
        this.staleAfter = properties.checks().consumerStaleAfter();
        this.clock = clock;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Set<String> types() {
        return Set.of(AlertTypes.CONSUMER_PAUSED);
    }

    @Override
    public List<Finding> run() throws SourceUnavailableException {
        Instant now = clock.instant();
        Map<String, List<ConsumerStatus>> replicas = new TreeMap<>();
        for (ConsumerStatus status : views.all()) {
            if (!isStale(status, now)) {
                replicas.computeIfAbsent(status.app() + "/" + status.consumer(), key -> new ArrayList<>()).add(status);
            }
        }
        List<Finding> findings = new ArrayList<>();
        replicas.forEach((subject, statuses) -> {
            List<String> paused = new ArrayList<>();
            for (ConsumerStatus status : statuses) {
                if (ConsumerGate.PAUSED_AUTO.equals(status.state())) {
                    paused.add(status.instance() + " (automatic: " + status.reason() + ")");
                } else if (ConsumerGate.PAUSED_MANUAL.equals(status.state())) {
                    paused.add(status.instance() + " (by hand: " + status.changedBy() + ")");
                }
            }
            if (!paused.isEmpty()) {
                Map<String, String> details = new LinkedHashMap<>();
                details.put("paused", String.join(", ", paused));
                details.put("replicas", Integer.toString(statuses.size()));
                details.put("topic", statuses.get(0).topic());
                findings.add(new Finding(AlertTypes.CONSUMER_PAUSED, subject, details));
            }
        });
        return findings;
    }

    private boolean isStale(ConsumerStatus status, Instant now) {
        try {
            return status.updatedAt() == null || Instant.parse(status.updatedAt()).isBefore(now.minus(staleAfter));
        } catch (DateTimeParseException e) {
            return true;
        }
    }
}
