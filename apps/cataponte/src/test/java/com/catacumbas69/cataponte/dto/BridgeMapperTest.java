package com.catacumbas69.cataponte.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.catacumbas69.cataponte.TestProperties;
import com.catacumbas69.cataponte.model.BridgeDocument;
import java.util.List;
import org.junit.jupiter.api.Test;

class BridgeMapperTest {

    private final BridgeMapper mapper = new BridgeMapper(TestProperties.properties(500));

    @Test
    void toDocument_appliesDefaults_orderedWithFailover() {
        BridgeRequest request = request(null, null);

        BridgeDocument bridge = mapper.toDocument(request);

        assertThat(bridge.isEnabled()).isTrue();
        assertThat(bridge.isPreserveKey()).isTrue();
        assertThat(bridge.isPreserveProperties()).isTrue();
        assertThat(bridge.getSource().getSubscriptionType()).isEqualTo("Failover");
        assertThat(bridge.isOrderedEffective()).isTrue();
    }

    @Test
    void toDocument_defaultsToShared_whenUnordered() {
        BridgeDocument bridge = mapper.toDocument(request(false, null));

        assertThat(bridge.getSource().getSubscriptionType()).isEqualTo("Shared");
        assertThat(bridge.isOrderedEffective()).isFalse();
    }

    @Test
    void toResponse_resolvesEffectiveMaxInFlight() {
        BridgeDocument bridge = mapper.toDocument(request(false, "Shared"));

        assertThat(mapper.toResponse(bridge).maxInFlight()).isEqualTo(500);
    }

    private static BridgeRequest request(Boolean ordered, String subscriptionType) {
        return new BridgeRequest(" fanout ", null, null,
                new BridgeRequest.Source("persistent://a/b/in", "sub", subscriptionType),
                List.of(new BridgeRequest.Destination("persistent://a/b/out")),
                null, null, ordered, null, null);
    }
}
