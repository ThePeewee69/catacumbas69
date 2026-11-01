package com.catacumbas69.cataponte.service;

import com.catacumbas69.catacommons.audit.SystemBridge;
import com.catacumbas69.catacommons.audit.SystemBridgeRegistry;
import com.catacumbas69.cataponte.model.BridgeDestination;
import com.catacumbas69.cataponte.model.BridgeDocument;
import com.catacumbas69.cataponte.model.BridgeSource;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Registers Cataponte's own system bridges ({@code audit-cataponte}, ADR 0009) directly in its bridge store,
 * instead of calling its own API over HTTP. Replaces catacommons' HTTP registry in this app only.
 */
@Component
public class LocalSystemBridgeRegistry implements SystemBridgeRegistry {

    private final BridgeService bridges;

    /**
     * Creates the registry.
     *
     * @param bridges bridge definitions
     */
    public LocalSystemBridgeRegistry(BridgeService bridges) {
        this.bridges = bridges;
    }

    @Override
    public void register(SystemBridge bridge) {
        BridgeDocument draft = new BridgeDocument();
        draft.setName(bridge.name());
        draft.setDescription(bridge.description());
        draft.setEnabled(true);
        draft.setSource(new BridgeSource(bridge.sourceTopic(), bridge.subscription(), "Failover"));
        draft.setDestinations(List.of(new BridgeDestination(bridge.destinationTopic())));
        draft.setOrdered(true);
        bridges.registerSystem(draft, AuditLog.SYSTEM_USER);
    }
}
