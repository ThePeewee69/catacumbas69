package com.catacumbas69.catacommons.consumers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.apache.pulsar.client.api.Consumer;
import org.junit.jupiter.api.Test;

class ConsumerGateTest {

    private final List<String> changes = new ArrayList<>();
    private final ConsumerGate gate = new ConsumerGate("audit", "persistent://catacumbas69/cataudit/audit", "cataudit",
            Set.of("mongodb"), "cataudit", Clock.fixed(Instant.parse("2026-11-06T20:00:00Z"), ZoneOffset.UTC),
            (g, action, actor, automatic) -> changes.add(action + " by " + actor + (automatic ? " (auto)" : "")));
    private final Consumer<?> consumer = mock(Consumer.class);

    @Test
    void runsOnceAttached() {
        assertThat(gate.state()).isEqualTo(ConsumerGate.STARTING);

        gate.attach(consumer, "dlq", 10);

        assertThat(gate.state()).isEqualTo(ConsumerGate.RUNNING);
        verify(consumer, never()).pause();
    }

    @Test
    void automaticPause_isResumedAutomatically() {
        gate.attach(consumer, "dlq", 10);

        assertThat(gate.pauseAutomatically("MongoDB unavailable")).isTrue();
        assertThat(gate.pauseAutomatically("again")).isFalse();
        assertThat(gate.state()).isEqualTo(ConsumerGate.PAUSED_AUTO);
        verify(consumer).pause();

        assertThat(gate.resumeAutomatically()).isTrue();
        assertThat(gate.state()).isEqualTo(ConsumerGate.RUNNING);
        verify(consumer).resume();
        assertThat(changes).containsExactly("paused by cataudit (auto)", "resumed by cataudit (auto)");
    }

    @Test
    void manualPause_isNotResumedAutomatically() {
        gate.attach(consumer, "dlq", 10);
        gate.pauseAutomatically("MongoDB unavailable");

        gate.pauseManually("10.0.0.1");

        assertThat(gate.resumeAutomatically()).isFalse();
        assertThat(gate.state()).isEqualTo(ConsumerGate.PAUSED_MANUAL);
        assertThat(gate.resumeManually("10.0.0.1")).isTrue();
        assertThat(gate.state()).isEqualTo(ConsumerGate.RUNNING);
    }

    @Test
    void aPausedGate_pausesANewConsumer() {
        gate.pauseManually("10.0.0.1");

        gate.attach(consumer, "dlq", 10);

        verify(consumer).pause();
        assertThat(gate.status("i1", null).state()).isEqualTo(ConsumerGate.PAUSED_MANUAL);
        assertThat(gate.status("i1", null).changedBy()).isEqualTo("10.0.0.1");
    }
}
