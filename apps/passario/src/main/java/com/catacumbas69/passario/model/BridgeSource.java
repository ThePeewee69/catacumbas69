package com.catacumbas69.passario.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Where a bridge reads from.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class BridgeSource {

    /** Subscription type used when none is stored. */
    public static final String DEFAULT_SUBSCRIPTION_TYPE = "Shared";

    /** Fully-qualified topic: {@code persistent://tenant/namespace/topic} (PUL-3). */
    private String topic;

    /** Subscription the bridge consumes with. */
    private String subscription;

    /** Pulsar subscription type name: Exclusive, Shared, Failover or Key_Shared. */
    private String subscriptionType = DEFAULT_SUBSCRIPTION_TYPE;

    /**
     * Returns the stored subscription type, or {@link #DEFAULT_SUBSCRIPTION_TYPE} when none is stored.
     *
     * @return the effective subscription type
     */
    public String subscriptionTypeOrDefault() {
        return subscriptionType == null || subscriptionType.isBlank() ? DEFAULT_SUBSCRIPTION_TYPE : subscriptionType;
    }
}
