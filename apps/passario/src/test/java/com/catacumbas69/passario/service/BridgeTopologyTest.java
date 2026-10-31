package com.catacumbas69.passario.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.catacumbas69.passario.TestBridges;
import com.catacumbas69.passario.model.BridgeDestination;
import com.catacumbas69.passario.model.BridgeDocument;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class BridgeTopologyTest {

    private static final String A = "persistent://home/media/a";
    private static final String B = "persistent://home/media/b";
    private static final String C = "persistent://home/media/c";
    private static final String D = "persistent://home/media/d";

    @Test
    void noLoop_forAChain() {
        assertThat(BridgeTopology.loopThrough(bridge("3", C, D), List.of(bridge("1", A, B), bridge("2", B, C))))
                .isEmpty();
    }

    @Test
    void detectsDirectLoop() {
        assertThat(BridgeTopology.loopThrough(bridge("2", B, A), List.of(bridge("1", A, B))))
                .hasValueSatisfying(path -> assertThat(path).contains("bridge-1").contains("bridge-2"));
    }

    @Test
    void detectsLoopThroughSeveralBridges_andAnyDestination() {
        List<BridgeDocument> others = List.of(bridge("1", A, B), bridge("2", B, C));

        assertThat(BridgeTopology.loopThrough(bridge("3", C, D, A), others))
                .hasValue(C + " → " + A + " ('bridge-3') → " + B + " ('bridge-1') → " + C + " ('bridge-2')");
    }

    @Test
    void ignoresDisabledBridges() {
        BridgeDocument disabled = bridge("1", A, B);
        disabled.setEnabled(false);

        assertThat(BridgeTopology.loopThrough(bridge("2", B, A), List.of(disabled))).isEmpty();
    }

    @Test
    void ignoresTheCandidatesOwnStoredCopy_whenItIsEdited() {
        BridgeDocument stored = bridge("1", A, B);
        BridgeDocument edited = bridge("1", B, C);

        assertThat(BridgeTopology.loopThrough(edited, List.of(stored))).isEmpty();
    }

    @Test
    void treatsPartitionsAsTheirTopic() {
        assertThat(BridgeTopology.loopThrough(bridge("2", B + "-partition-0", A), List.of(bridge("1", A, B))))
                .isPresent();
    }

    @Test
    void bridgesInLoops_returnsOnlyBridgesOnTheCycle() {
        List<BridgeDocument> enabled = List.of(bridge("1", A, B), bridge("2", B, A), bridge("3", B, C));

        assertThat(BridgeTopology.bridgesInLoops(enabled)).containsExactlyInAnyOrder("1", "2");
    }

    private static BridgeDocument bridge(String id, String source, String... destinations) {
        BridgeDocument bridge = TestBridges.unordered(id);
        bridge.getSource().setTopic(source);
        bridge.setDestinations(new ArrayList<>(Arrays.stream(destinations).map(BridgeDestination::new).toList()));
        return bridge;
    }
}
