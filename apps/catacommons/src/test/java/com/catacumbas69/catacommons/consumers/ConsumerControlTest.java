package com.catacumbas69.catacommons.consumers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.catacumbas69.catacommons.CatacommonsProperties;
import com.catacumbas69.catacommons.events.EventPublisher;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.pulsar.client.api.PulsarClient;
import org.junit.jupiter.api.Test;

class ConsumerControlTest {

    private final AtomicBoolean mongoUp = new AtomicBoolean(false);
    private final ConnectionProbe mongo = new ConnectionProbe() {
        @Override
        public String name() {
            return ConnectionProbe.MONGODB;
        }

        @Override
        public ProbeResult probe() {
            return mongoUp.get() ? ProbeResult.up(3) : ProbeResult.down(5000, "timeout");
        }
    };
    private final EventPublisher events = mock(EventPublisher.class);
    private final ConsumerControl control = new ConsumerControl(mock(PulsarClient.class), "cataudit",
            new InstanceId("cataudit"), List.of(mongo), events, mock(ScheduledExecutorService.class),
            new CatacommonsProperties.Consumers(true, Duration.ofSeconds(10), Duration.ofSeconds(5), 10),
            Clock.systemUTC());
    private final ConsumerGate gate = control.register("audit", "persistent://catacumbas69/cataudit/audit",
            "cataudit", Set.of(ConnectionProbe.MONGODB));

    @Test
    void resumesAutomatically_onlyOnceTheDependencyAnswers() {
        gate.pauseAutomatically("MongoDB unavailable");

        control.resumeWhereDependenciesAnswer();
        assertThat(gate.state()).isEqualTo(ConsumerGate.PAUSED_AUTO);

        mongoUp.set(true);
        control.resumeWhereDependenciesAnswer();
        assertThat(gate.state()).isEqualTo(ConsumerGate.STARTING);
    }

    @Test
    void handlesCommandsForThisReplicaOrAll() {
        control.handle(new ConsumerCommand("c1", "someone-else", "*", ConsumerCommand.PAUSE, "10.0.0.1", null));
        assertThat(gate.isPaused()).isFalse();

        control.handle(new ConsumerCommand("c2", "*", "audit", ConsumerCommand.PAUSE, "10.0.0.1", null));
        assertThat(gate.state()).isEqualTo(ConsumerGate.PAUSED_MANUAL);

        control.handle(new ConsumerCommand("c3", control.instance(), "*", ConsumerCommand.RESUME, "10.0.0.1", null));
        assertThat(gate.isPaused()).isFalse();
        verify(events).publish(eq(ConsumerControl.ENTITY), eq("paused"), eq(control.instance() + "/audit"), any());
    }

    @Test
    void check_runsEveryProbe_andPublishesTheResult() {
        ConnectionCheck check = control.check("10.0.0.1");

        assertThat(check.results()).containsKey(ConnectionProbe.MONGODB);
        assertThat(check.allUp()).isFalse();
        assertThat(control.lastCheck()).isEqualTo(check);
        verify(events).publish(eq(ConsumerControl.ENTITY), eq("checked"), eq(control.instance()), any());
    }

    @Test
    void unavailable_namesTheDependencyThatIsDown() {
        assertThat(control.unavailable(gate)).hasValue("mongodb unavailable: timeout");

        mongoUp.set(true);
        assertThat(control.unavailable(gate)).isEmpty();
    }

    @Test
    void namesTheConsumerAfterTheAppAndTheInstance() {
        assertThat(control.consumerName()).isEqualTo("cataudit@" + control.instance());
    }
}
