package com.catacumbas69.catavigia.checks;

import com.catacumbas69.catacommons.consumers.DeadLetters;
import com.catacumbas69.catavigia.model.AlertTypes;
import com.catacumbas69.catavigia.checks.SubscriptionsReader.SubscriptionBacklog;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * {@code poison}: a dead-letter topic has messages waiting on its {@code dlq} subscription (ADR 0015). Read from
 * Catametricas, so it follows its collection interval.
 */
@Component
public class PoisonCheck implements Check {

    /** Check name. */
    public static final String NAME = "poison";

    private final SubscriptionsReader subscriptions;

    /**
     * Creates the check.
     *
     * @param subscriptions reads the subscriptions from Catametricas
     */
    public PoisonCheck(SubscriptionsReader subscriptions) {
        this.subscriptions = subscriptions;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Set<String> types() {
        return Set.of(AlertTypes.POISON);
    }

    @Override
    public List<Finding> run() throws SourceUnavailableException {
        List<Finding> findings = new ArrayList<>();
        for (SubscriptionBacklog sub : subscriptions.read()) {
            if (isDeadLetterSubscription(sub) && sub.backlog() > 0) {
                findings.add(new Finding(AlertTypes.POISON, sub.topic(),
                        Map.of("backlog", Long.toString(sub.backlog()))));
            }
        }
        return findings;
    }

    /**
     * Tells whether a subscription is a DLQ's own.
     *
     * @param sub a subscription
     * @return {@code true} for the {@code dlq} subscription of a topic ending in {@code -DLQ}
     */
    static boolean isDeadLetterSubscription(SubscriptionBacklog sub) {
        return sub.baseName().endsWith("-DLQ") && DeadLetters.SUBSCRIPTION.equals(sub.subscription());
    }
}
