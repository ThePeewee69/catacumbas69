package com.catacumbas69.catametricas.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TopicNamesTest {

    @Test
    void parsesPersistentTopic() {
        assertThat(TopicNames.parse("persistent://home/media/orders"))
                .isEqualTo(new TopicNames("home", "media", "orders", "orders"));
    }

    @Test
    void foldsPartitionIntoBaseName() {
        TopicNames names = TopicNames.parse("persistent://home/media/orders-partition-12");

        assertThat(names.name()).isEqualTo("orders-partition-12");
        assertThat(names.baseName()).isEqualTo("orders");
    }

    @Test
    void parsesNonPersistentTopic() {
        assertThat(TopicNames.parse("non-persistent://public/default/ticks").tenant()).isEqualTo("public");
    }

    @Test
    void recognisesSystemTopics() {
        assertThat(TopicNames.parse("persistent://home/media/__change_events").isSystemTopic()).isTrue();
        assertThat(TopicNames.parse("persistent://home/media/orders").isSystemTopic()).isFalse();
    }

    @Test
    void rejectsNamesWithoutNamespace() {
        assertThatThrownBy(() -> TopicNames.parse("persistent://home/orders"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
