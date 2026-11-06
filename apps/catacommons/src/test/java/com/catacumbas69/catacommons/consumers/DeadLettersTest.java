package com.catacumbas69.catacommons.consumers;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.pulsar.client.api.DeadLetterPolicy;
import org.junit.jupiter.api.Test;

class DeadLettersTest {

    @Test
    void namesTheDeadLetterTopicLikePulsar() {
        assertThat(DeadLetters.topicOf("persistent://catacumbas69/cataudit/audit", "cataudit"))
                .isEqualTo("persistent://catacumbas69/cataudit/audit-cataudit-DLQ");
    }

    @Test
    void policy_movesAMessageAfterNDeliveries() {
        DeadLetterPolicy policy = DeadLetters.policy("persistent://a/b/c", "s", 10);

        assertThat(policy.getMaxRedeliverCount()).isEqualTo(9);
        assertThat(policy.getDeadLetterTopic()).isEqualTo("persistent://a/b/c-s-DLQ");
        assertThat(policy.getInitialSubscriptionName()).isEqualTo(DeadLetters.SUBSCRIPTION);
    }

    @Test
    void parse_keepsTheSettingInRange() {
        assertThat(DeadLetters.parse("5", 10)).isEqualTo(5);
        assertThat(DeadLetters.parse(" 5 ", 10)).isEqualTo(5);
        assertThat(DeadLetters.parse("1", 10)).isEqualTo(10);
        assertThat(DeadLetters.parse("1000", 10)).isEqualTo(10);
        assertThat(DeadLetters.parse("x", 10)).isEqualTo(10);
        assertThat(DeadLetters.maxDeliveries(null, 7)).isEqualTo(7);
    }
}
