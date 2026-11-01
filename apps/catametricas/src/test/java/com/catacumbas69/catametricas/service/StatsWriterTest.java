package com.catacumbas69.catametricas.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.catacumbas69.catametricas.model.Schema;
import com.catacumbas69.catametricas.model.TopicSnapshot;
import com.mongodb.client.result.UpdateResult;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.FindAndReplaceOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

class StatsWriterTest {

    private static final String ORDERS = "persistent://home/media/orders";
    private static final Instant NOW = Instant.parse("2026-11-01T09:00:30Z");

    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);
    private final BulkOperations bulk = mock(BulkOperations.class, RETURNS_SELF);
    private final UpdateResult updated = mock(UpdateResult.class);
    private final StatsWriter writer = new StatsWriter(mongoTemplate);

    @BeforeEach
    void wire() {
        when(mongoTemplate.bulkOps(any(BulkOperations.BulkMode.class), anyString())).thenReturn(bulk);
        when(mongoTemplate.updateMulti(any(Query.class), any(Update.class), eq(Schema.SNAPSHOTS))).thenReturn(updated);
        when(mongoTemplate.exists(any(Query.class), eq(Schema.SNAPSHOTS))).thenReturn(true);
    }

    @Test
    void countsTheDifferenceSinceTheSnapshotItReplaced() {
        replacing(snapshot(100, NOW.minusSeconds(60)));

        StatsWriter.Result result = writer.write(reading(sample(150)), NOW);

        assertThat(result.advanced()).isEqualTo(1);
        assertThat(increments(Schema.MINUTES)).containsEntry(Schema.MSGS_IN, 50L);
        verify(mongoTemplate).bulkOps(BulkOperations.BulkMode.UNORDERED, Schema.HOURS);
        verify(mongoTemplate).bulkOps(BulkOperations.BulkMode.UNORDERED, Schema.TOTALS);
    }

    @Test
    void countsNothing_whenAnotherInstanceAlreadyStoredANewerSample() {
        when(mongoTemplate.findAndReplace(any(Query.class), any(TopicSnapshot.class), any(FindAndReplaceOptions.class),
                eq(TopicSnapshot.class), eq(Schema.SNAPSHOTS), eq(TopicSnapshot.class)))
                .thenThrow(new DuplicateKeyException("newer"));

        StatsWriter.Result result = writer.write(reading(sample(150)), NOW);

        assertThat(result.advanced()).isZero();
        assertThat(result.skipped()).isEqualTo(1);
        verify(mongoTemplate, never()).bulkOps(any(BulkOperations.BulkMode.class), anyString());
    }

    @Test
    void countsANewTopicFully_inANamespaceSeenBefore() {
        replacing(null);

        writer.write(reading(sample(40)), NOW);

        assertThat(increments(Schema.MINUTES)).containsEntry(Schema.MSGS_IN, 40L);
    }

    @Test
    void onlySetsTheStartingPoint_onTheFirstCollectionOfANamespace() {
        when(mongoTemplate.exists(any(Query.class), eq(Schema.SNAPSHOTS))).thenReturn(false);
        replacing(null);

        StatsWriter.Result result = writer.write(reading(sample(40)), NOW);

        assertThat(result.baseline()).isTrue();
        assertThat(increments(Schema.MINUTES)).containsEntry(Schema.MSGS_IN, 0L);
    }

    @Test
    void replacesOnlyOlderSnapshots() {
        replacing(null);

        writer.write(reading(sample(40)), NOW);

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).findAndReplace(query.capture(), any(TopicSnapshot.class),
                any(FindAndReplaceOptions.class), eq(TopicSnapshot.class), eq(Schema.SNAPSHOTS),
                eq(TopicSnapshot.class));
        Document filter = query.getValue().getQueryObject();
        assertThat(filter.get("_id")).isEqualTo(ORDERS);
        assertThat(filter.get(Schema.COLLECTED_AT, Document.class).get("$lt")).isEqualTo(NOW);
    }

    @Test
    void marksTopicsMissingFromTheNamespaceAsGone_butNotThoseThatFailed() {
        when(updated.getModifiedCount()).thenReturn(2L);

        StatsWriter.Result result = writer.write(
                new PulsarReading("home/media", List.of(), Set.of("persistent://home/media/broken")), NOW);

        assertThat(result.deactivated()).isEqualTo(2);
        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).updateMulti(query.capture(), any(Update.class), eq(Schema.SNAPSHOTS));
        Document filter = query.getValue().getQueryObject();
        assertThat(filter).containsEntry(Schema.TENANT, "home").containsEntry(Schema.NAMESPACE, "media")
                .containsEntry(Schema.ACTIVE, true);
        assertThat(filter.get("_id", Document.class).get("$nin"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.COLLECTION)
                .containsExactly("persistent://home/media/broken");
        assertThat(filter.get(Schema.COLLECTED_AT, Document.class).get("$lt")).isEqualTo(NOW);
    }

    private void replacing(TopicSnapshot before) {
        when(mongoTemplate.findAndReplace(any(Query.class), any(TopicSnapshot.class), any(FindAndReplaceOptions.class),
                eq(TopicSnapshot.class), eq(Schema.SNAPSHOTS), eq(TopicSnapshot.class))).thenReturn(before);
    }

    /** Returns the {@code $inc} of the single upsert sent to a collection. */
    private Document increments(String collection) {
        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        verify(bulk, times(3)).upsert(any(Query.class), update.capture());
        int index = List.of(Schema.MINUTES, Schema.HOURS, Schema.TOTALS).indexOf(collection);
        return update.getAllValues().get(index).getUpdateObject().get("$inc", Document.class);
    }

    private static PulsarReading reading(TopicSample sample) {
        return new PulsarReading("home/media", List.of(sample), Set.of());
    }

    private static TopicSample sample(long msgIn) {
        return new TopicSample(ORDERS, TopicNames.parse(ORDERS), msgIn, 0, msgIn * 10, 0, 1, 0, 10, 0, 3, 30, 100, 1,
                1, 0);
    }

    private static TopicSnapshot snapshot(long msgIn, Instant collectedAt) {
        return new TopicSnapshot(ORDERS, "home", "media", "orders", "orders", true, collectedAt, msgIn, 0, msgIn * 10,
                0, 1, 0, 10, 0, 3, 30, 100, 1, 1, 0);
    }
}
