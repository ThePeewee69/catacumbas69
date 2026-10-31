package com.catacumbas69.catametricas.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CounterDeltaTest {

    @Test
    void countsTheIncrease() {
        assertThat(CounterDelta.between(100L, 150, false)).isEqualTo(50);
    }

    @Test
    void countsTheWholeValue_afterAReset() {
        assertThat(CounterDelta.between(1_000L, 30, false)).isEqualTo(30);
    }

    @Test
    void countsEverything_forATopicCreatedSinceTheLastCollection() {
        assertThat(CounterDelta.between(null, 42, false)).isEqualTo(42);
    }

    @Test
    void countsNothing_onTheVeryFirstCollection() {
        assertThat(CounterDelta.between(null, 1_000_000, true)).isZero();
    }

    @Test
    void neverNegative() {
        assertThat(CounterDelta.between(5L, -1, false)).isZero();
    }
}
