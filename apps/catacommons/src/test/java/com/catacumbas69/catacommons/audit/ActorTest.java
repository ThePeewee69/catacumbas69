package com.catacumbas69.catacommons.audit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ActorTest {

    @Test
    void resolve_prefersTheUserHeader() {
        assertThat(Actor.resolve(" peewee69 ", "192.168.86.20, 10.42.0.1", "10.42.0.7")).isEqualTo("peewee69");
    }

    @Test
    void resolve_withoutUser_usesTheFirstForwardedAddress() {
        assertThat(Actor.resolve(null, "192.168.86.20, 10.42.0.1", "10.42.0.7")).isEqualTo("192.168.86.20");
        assertThat(Actor.resolve("  ", "192.168.86.20", "10.42.0.7")).isEqualTo("192.168.86.20");
    }

    @Test
    void resolve_withNeither_usesTheRemoteAddress() {
        assertThat(Actor.resolve(null, " ", "10.42.0.7")).isEqualTo("10.42.0.7");
    }
}
