package com.catacumbas69.passario.document;

import lombok.Data;

@Data
public class BridgeSource {
    /** Fully-qualified topic: persistent://tenant/namespace/topic */
    private String topic;

    /** Pulsar subscription name to use when consuming */
    private String subscription;

    /** Exclusive | Shared | Failover | Key_Shared  (default: Shared) */
    private String subscriptionType = "Shared";
}
