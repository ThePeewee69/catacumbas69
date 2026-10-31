package com.catacumbas69.cataponte;

import com.catacumbas69.cataponte.model.BridgeDestination;
import com.catacumbas69.cataponte.model.BridgeDocument;
import com.catacumbas69.cataponte.model.BridgeSource;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * Builds bridge documents for tests.
 */
public final class TestBridges {

    public static final String SOURCE = "persistent://home/media/in";
    public static final String DEST_A = "persistent://home/media/out-a";
    public static final String DEST_B = "persistent://home/media/out-b";

    private TestBridges() {
    }

    public static BridgeDocument bridge(String id, String subscriptionType, String... destinations) {
        BridgeDocument bridge = new BridgeDocument();
        bridge.setId(id);
        bridge.setName("bridge-" + id);
        bridge.setEnabled(true);
        bridge.setSource(new BridgeSource(SOURCE, "cataponte-" + id, subscriptionType));
        bridge.setDestinations(new ArrayList<>(Arrays.stream(destinations).map(BridgeDestination::new).toList()));
        bridge.setVersion(1L);
        return bridge;
    }

    public static BridgeDocument unordered(String id, String... destinations) {
        return bridge(id, "Shared", destinations);
    }

    public static BridgeDocument ordered(String id, String... destinations) {
        return bridge(id, "Failover", destinations);
    }
}
