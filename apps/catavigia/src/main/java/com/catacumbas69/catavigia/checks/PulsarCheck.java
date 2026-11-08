package com.catacumbas69.catavigia.checks;

import com.catacumbas69.catavigia.config.CatavigiaProperties;
import com.catacumbas69.catavigia.model.AlertTypes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.apache.pulsar.common.naming.TopicVersion;
import org.springframework.stereotype.Component;

/**
 * {@code pulsar.broker.down} and {@code pulsar.bookie.down}, from Pulsar's admin API (orch.md decision 79):
 * <ul>
 *   <li>fewer active brokers than {@code catavigia.checks.expected-brokers}: {@code pulsar.broker.down}, subject
 *       {@code brokers};</li>
 *   <li>brokers active but the broker health check failing (it writes and reads a message, so the storage does
 *       not answer): {@code pulsar.bookie.down}, subject {@code healthcheck}. Pulsar's admin API lists every known
 *       bookie, running or not, so bookies are not counted.</li>
 * </ul>
 *
 * <p>If the admin API does not answer at all, the check cannot run: the task is delivered again and the consumer
 * pauses (with Pulsar down no check runs, orch.md decision 79).
 */
@Component
public class PulsarCheck implements Check {

    /** Check name. */
    public static final String NAME = "pulsar";

    private final PulsarAdmin admin;
    private final int expectedBrokers;

    /**
     * Creates the check.
     *
     * @param admin Pulsar admin client
     * @param properties application properties, for the expected number of brokers
     */
    public PulsarCheck(PulsarAdmin admin, CatavigiaProperties properties) {
        this.admin = admin;
        this.expectedBrokers = properties.checks().expectedBrokers();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Set<String> types() {
        return Set.of(AlertTypes.PULSAR_BROKER_DOWN, AlertTypes.PULSAR_BOOKIE_DOWN);
    }

    @Override
    public List<Finding> run() throws SourceUnavailableException {
        List<String> brokers = new ArrayList<>();
        try {
            for (String cluster : admin.clusters().getClusters()) {
                brokers.addAll(admin.brokers().getActiveBrokers(cluster));
            }
        } catch (PulsarAdminException | RuntimeException e) {
            throw new SourceUnavailableException("Pulsar admin API: " + e.getMessage(), e);
        }
        List<Finding> findings = new ArrayList<>();
        if (brokers.size() < expectedBrokers) {
            Map<String, String> details = new LinkedHashMap<>();
            details.put("active", Integer.toString(brokers.size()));
            details.put("expected", Integer.toString(expectedBrokers));
            details.put("brokers", brokers.isEmpty() ? "none" : String.join(", ", brokers));
            findings.add(new Finding(AlertTypes.PULSAR_BROKER_DOWN, "brokers", details));
        }
        if (!brokers.isEmpty()) {
            try {
                admin.brokers().healthcheck(TopicVersion.V2);
            } catch (PulsarAdminException | RuntimeException e) {
                Map<String, String> details = new LinkedHashMap<>();
                details.put("error", String.valueOf(e.getMessage()));
                details.put("brokers", String.join(", ", brokers));
                findings.add(new Finding(AlertTypes.PULSAR_BOOKIE_DOWN, "healthcheck", details));
            }
        }
        return findings;
    }
}
