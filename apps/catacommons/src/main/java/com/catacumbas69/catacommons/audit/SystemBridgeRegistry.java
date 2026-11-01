package com.catacumbas69.catacommons.audit;

/**
 * Creates or updates a system bridge in Cataponte. Implementations are idempotent: registering the same
 * bridge again changes nothing.
 */
@FunctionalInterface
public interface SystemBridgeRegistry {

    /**
     * Creates the bridge, or updates its definition if it differs. An enabled state changed by a user is
     * kept.
     *
     * @param bridge the bridge
     * @throws RuntimeException if Cataponte cannot be reached or refuses the bridge
     */
    void register(SystemBridge bridge);
}
