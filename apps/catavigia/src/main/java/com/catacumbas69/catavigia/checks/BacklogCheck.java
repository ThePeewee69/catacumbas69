package com.catacumbas69.catavigia.checks;

import com.catacumbas69.catacommons.alerts.AlertSetting;
import com.catacumbas69.catacommons.settings.SharedSettings;
import com.catacumbas69.catavigia.checks.SubscriptionsReader.SubscriptionBacklog;
import com.catacumbas69.catavigia.model.AlertTypes;
import com.catacumbas69.catavigia.model.BacklogLimit;
import com.catacumbas69.catavigia.service.BacklogLimitService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * {@code backlog}: a subscription's backlog, partitions summed, is above its own limit or the global default
 * ({@code alerts.backlog-limit}, orch.md decision 70). The {@code dlq} subscription of a DLQ ({@code poison} covers
 * it) and Pulsar's own subscriptions ({@code __*}) are left out (orch.md decision 79).
 */
@Component
public class BacklogCheck implements Check {

    /** Check name. */
    public static final String NAME = "backlog";

    private final SubscriptionsReader subscriptions;
    private final BacklogLimitService limits;
    private final SharedSettings settings;

    /**
     * Creates the check.
     *
     * @param subscriptions reads the subscriptions from Catametricas
     * @param limits the limits set per subscription
     * @param settings the settings topic, for the global default
     */
    public BacklogCheck(SubscriptionsReader subscriptions, BacklogLimitService limits,
                        ObjectProvider<SharedSettings> settings) {
        this.subscriptions = subscriptions;
        this.limits = limits;
        this.settings = settings.getIfAvailable();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Set<String> types() {
        return Set.of(AlertTypes.BACKLOG);
    }

    @Override
    public List<Finding> run() throws SourceUnavailableException {
        long global = AlertSetting.BACKLOG_LIMIT.value(settings);
        Map<String, BacklogLimit> own = limits.byId();
        List<Finding> findings = new ArrayList<>();
        for (SubscriptionBacklog sub : subscriptions.read()) {
            if (isLeftOut(sub)) {
                continue;
            }
            BacklogLimit limit = own.get(BacklogLimit.idOf(sub.topic(), sub.subscription()));
            long applies = limit == null ? global : limit.limit();
            if (sub.backlog() > applies) {
                Map<String, String> details = new LinkedHashMap<>();
                details.put("topic", sub.topic());
                details.put("subscription", sub.subscription());
                details.put("backlog", Long.toString(sub.backlog()));
                details.put("limit", Long.toString(applies));
                details.put("limitSource", limit == null ? "global" : "subscription");
                findings.add(new Finding(AlertTypes.BACKLOG, subjectOf(sub.topic(), sub.subscription()), details));
            }
        }
        return findings;
    }

    /**
     * Returns the subject of a subscription's {@code backlog} alert.
     *
     * @param topic topic without partition suffix
     * @param subscription subscription name
     * @return {@code <topic>|<subscription>}
     */
    public static String subjectOf(String topic, String subscription) {
        return topic + "|" + subscription;
    }

    static boolean isLeftOut(SubscriptionBacklog sub) {
        return sub.subscription().startsWith("__") || PoisonCheck.isDeadLetterSubscription(sub);
    }
}
