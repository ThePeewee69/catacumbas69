package com.catacumbas69.passario.model;

import static com.catacumbas69.passario.TestBridges.DEST_A;
import static com.catacumbas69.passario.TestBridges.DEST_B;
import static org.assertj.core.api.Assertions.assertThat;

import com.catacumbas69.passario.TestBridges;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

@SuppressWarnings("deprecation")
class BridgeDocumentTest {

    @Test
    void resolveDestinationTopics_fallsBackToLegacySingleDestination() {
        BridgeDocument bridge = TestBridges.unordered("1");
        bridge.setDestinations(new ArrayList<>());
        bridge.setDestination(new BridgeDestination(DEST_A));

        assertThat(bridge.resolveDestinationTopics()).containsExactly(DEST_A);
    }

    @Test
    void resolveDestinationTopics_dropsBlanksAndDuplicatesKeepingOrder() {
        BridgeDocument bridge = TestBridges.unordered("1", DEST_B, " ", DEST_A, DEST_B);
        bridge.getDestinations().add(null);

        assertThat(bridge.resolveDestinationTopics()).containsExactly(DEST_B, DEST_A);
    }

    @Test
    void isOrderedEffective_derivesFromSubscriptionType_whenFlagIsMissing() {
        assertThat(TestBridges.unordered("1", DEST_A).isOrderedEffective()).isFalse();
        assertThat(TestBridges.ordered("2", DEST_A).isOrderedEffective()).isTrue();
        assertThat(TestBridges.bridge("3", "Key_Shared", DEST_A).isOrderedEffective()).isTrue();
    }

    @Test
    void isOrderedEffective_usesExplicitFlag() {
        BridgeDocument bridge = TestBridges.ordered("1", DEST_A);
        bridge.setOrdered(false);

        assertThat(bridge.isOrderedEffective()).isFalse();
    }

    @Test
    void copyDefinitionFrom_keepsIdentityVersionAndClearsLegacyField() {
        BridgeDocument existing = TestBridges.unordered("1", DEST_A);
        existing.setVersion(7L);
        existing.setDestination(new BridgeDestination("persistent://legacy/x/y"));
        BridgeDocument changes = TestBridges.ordered("other", DEST_B);
        changes.setName("renamed");
        changes.setVersion(99L);

        existing.copyDefinitionFrom(changes);

        assertThat(existing.getId()).isEqualTo("1");
        assertThat(existing.getVersion()).isEqualTo(7L);
        assertThat(existing.getName()).isEqualTo("renamed");
        assertThat(existing.getDestination()).isNull();
        assertThat(existing.resolveDestinationTopics()).isEqualTo(List.of(DEST_B));
    }
}
