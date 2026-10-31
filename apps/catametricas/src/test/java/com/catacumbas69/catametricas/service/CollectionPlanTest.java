package com.catacumbas69.catametricas.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.catacumbas69.catametricas.model.TopicSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CollectionPlanTest {

    private static final String ORDERS = "persistent://home/media/orders";
    private static final String OLD = "persistent://home/media/old";
    private static final String OTHER_NS = "persistent://home/logs/app";

    @Test
    void firstCollection_isABaseline() {
        PulsarReading reading = reading(List.of(sample(ORDERS, 500, 400)), Set.of("home/media"), Set.of());

        CollectionPlan plan = CollectionPlan.of(Map.of(), reading);

        assertThat(plan.traffic()).singleElement()
                .satisfies(t -> assertThat(t.msgsIn()).isZero())
                .satisfies(t -> assertThat(t.msgsOut()).isZero());
    }

    @Test
    void laterCollections_countTheDifference_andNewTopicsFully() {
        Map<String, TopicSnapshot> previous = Map.of(ORDERS, snapshot(ORDERS, 500, 400, true));
        String fresh = "persistent://home/media/fresh";
        PulsarReading reading = reading(List.of(sample(ORDERS, 650, 420), sample(fresh, 7, 3)),
                Set.of("home/media"), Set.of());

        CollectionPlan plan = CollectionPlan.of(previous, reading);

        assertThat(plan.traffic()).extracting(t -> t.sample().topic() + " " + t.msgsIn() + "/" + t.msgsOut())
                .containsExactly(ORDERS + " 150/20", fresh + " 7/3");
    }

    @Test
    void deactivatesOnlyTopicsMissingFromAListedNamespace() {
        Map<String, TopicSnapshot> previous = Map.of(
                ORDERS, snapshot(ORDERS, 1, 1, true),
                OLD, snapshot(OLD, 1, 1, true),
                OTHER_NS, snapshot(OTHER_NS, 1, 1, true));
        // home/logs could not be listed: its topic must not be marked as deleted
        PulsarReading reading = reading(List.of(sample(ORDERS, 2, 2)), Set.of("home/media"), Set.of());

        assertThat(CollectionPlan.of(previous, reading).deactivate()).containsExactly(OLD);
    }

    @Test
    void doesNotDeactivateTopicsWhoseStatisticsFailed() {
        Map<String, TopicSnapshot> previous = Map.of(OLD, snapshot(OLD, 1, 1, true), ORDERS,
                snapshot(ORDERS, 1, 1, true));
        PulsarReading reading = reading(List.of(sample(ORDERS, 2, 2)), Set.of("home/media"), Set.of(OLD));

        assertThat(CollectionPlan.of(previous, reading).deactivate()).isEmpty();
    }

    private static PulsarReading reading(List<TopicSample> samples, Set<String> listed, Set<String> failed) {
        return new PulsarReading(samples, listed, failed);
    }

    static TopicSample sample(String topic, long in, long out) {
        return new TopicSample(topic, TopicNames.parse(topic), in, out, in * 10, out * 10, 1.5, 1.0, 15, 10,
                3, 30, 1000, 1, 1, 1);
    }

    private static TopicSnapshot snapshot(String topic, long in, long out, boolean active) {
        TopicNames names = TopicNames.parse(topic);
        return new TopicSnapshot(topic, names.tenant(), names.namespace(), names.name(), names.baseName(), active,
                Instant.EPOCH, in, out, in * 10, out * 10, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}
