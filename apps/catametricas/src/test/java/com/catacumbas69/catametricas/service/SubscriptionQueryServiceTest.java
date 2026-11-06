package com.catacumbas69.catametricas.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.catacumbas69.catametricas.dto.SubscriptionSeriesResponse;
import com.catacumbas69.catametricas.dto.SubscriptionStatsResponse;
import com.catacumbas69.catametricas.model.Schema;
import com.catacumbas69.catametricas.model.SubscriptionSnapshot;
import com.catacumbas69.catametricas.model.TopicSnapshot;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

class SubscriptionQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-06T09:00:00Z");

    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);
    private final SubscriptionQueryService service = new SubscriptionQueryService(mongoTemplate);

    @Test
    void subscriptions_listsEverySubscriptionOfEveryPartition_byTopicAndName() {
        when(mongoTemplate.find(any(Query.class), eq(TopicSnapshot.class), eq(Schema.SNAPSHOTS))).thenReturn(List.of(
                snapshot("orders-partition-1", List.of(sub("b", 4), sub("a", 1))),
                snapshot("orders-partition-0", List.of(sub("a", 2))),
                snapshot("old", null)));

        List<SubscriptionStatsResponse> subs = service.subscriptions("home", "media", null);

        assertThat(subs).extracting(sub -> sub.name() + "/" + sub.subscription() + "=" + sub.backlog())
                .containsExactly("orders-partition-0/a=2", "orders-partition-1/a=1", "orders-partition-1/b=4");
    }

    @Test
    void subscriptions_ofATopic_filterByItsBaseName_andNeedItsNamespace() {
        service.subscriptions("home", "media", "orders");

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).find(query.capture(), eq(TopicSnapshot.class), eq(Schema.SNAPSHOTS));
        assertThat(query.getValue().getQueryObject()).containsEntry(Schema.BASE_NAME, "orders")
                .containsEntry(Schema.ACTIVE, true);
        assertThatThrownBy(() -> service.subscriptions("home", null, "orders"))
                .isInstanceOf(InvalidQueryException.class);
    }

    @Test
    void series_sumsPartitions_keepsTheLastSampleAndTheLargestBacklogOfEachBucket() {
        SeriesQuery query = SeriesQuery.of("home", "media", "topic", NOW, NOW.plusSeconds(600), "5m");
        when(mongoTemplate.find(any(Query.class), eq(Document.class), eq(Schema.MINUTES))).thenReturn(List.of(
                minute(NOW, 10, 12, 5), minute(NOW, 1, 1, 1),
                minute(NOW.plusSeconds(60), 3, 20, 7),
                minute(NOW.plusSeconds(600), 0, 0, 2)));

        SubscriptionSeriesResponse series = service.series(query, "orders", "audit.v1");

        assertThat(series.times()).hasSize(3);
        assertThat(series.backlog()).containsExactly(3L, null, 0L);
        assertThat(series.backlogMax()).containsExactly(20L, null, 0L);
        assertThat(series.msgsOut()).containsExactly(13L, 0L, 2L);
        ArgumentCaptor<Query> history = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).find(history.capture(), eq(Document.class), eq(Schema.MINUTES));
        assertThat(history.getValue().getQueryObject()).containsEntry(Schema.BASE_NAME, "orders")
                .containsKey("subs.audit%2Ev1");
    }

    private static TopicSnapshot snapshot(String name, List<SubscriptionSnapshot> subs) {
        return new TopicSnapshot("persistent://home/media/" + name, "home", "media", name,
                name.replaceAll("-partition-\\d+$", ""), true, NOW, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                subs == null ? 0 : subs.size(), 0, 0, subs);
    }

    private static SubscriptionSnapshot sub(String name, long backlog) {
        return new SubscriptionSnapshot(name, "Shared", backlog, 0, 1, 0, 0, 0);
    }

    private static Document minute(Instant at, long backlog, long backlogMax, long msgsOut) {
        return new Document(Schema.MINUTE, Date.from(at)).append(Schema.SUBS, new Document("audit%2Ev1",
                new Document(Schema.SUB_BACKLOG, backlog).append(Schema.SUB_BACKLOG_MAX, backlogMax)
                        .append(Schema.MSGS_OUT, msgsOut)));
    }
}
