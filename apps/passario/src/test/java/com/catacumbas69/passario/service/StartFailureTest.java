package com.catacumbas69.passario.service;

import static com.catacumbas69.passario.TestBridges.DEST_A;
import static org.assertj.core.api.Assertions.assertThat;

import com.catacumbas69.passario.TestBridges;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class StartFailureTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration INITIAL = Duration.ofSeconds(1);
    private static final Duration MAX = Duration.ofSeconds(60);

    @Test
    void next_doublesDelayPerAttempt_upToTheCap() {
        var bridge = TestBridges.unordered("1", DEST_A);
        StartFailure failure = null;
        long[] expectedSeconds = {1, 2, 4, 8, 16, 32, 60, 60};
        for (long expected : expectedSeconds) {
            failure = StartFailure.next(failure, bridge, "boom", NOW, INITIAL, MAX);
            assertThat(Duration.between(NOW, failure.nextAttemptAt()).getSeconds()).isEqualTo(expected);
        }
        assertThat(failure.attempts()).isEqualTo(expectedSeconds.length);
    }

    @Test
    void isDue_onlyAfterTheDelay() {
        StartFailure failure = StartFailure.next(null, TestBridges.unordered("1", DEST_A), "boom", NOW, INITIAL, MAX);

        assertThat(failure.isDue(NOW)).isFalse();
        assertThat(failure.isDue(NOW.plusSeconds(1))).isTrue();
    }
}
