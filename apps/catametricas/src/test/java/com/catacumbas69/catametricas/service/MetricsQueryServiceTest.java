package com.catacumbas69.catametricas.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.catacumbas69.catametricas.dto.TopicStatsResponse;
import com.catacumbas69.catametricas.model.Schema;
import com.catacumbas69.catametricas.model.TopicSnapshot;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

class MetricsQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-01T09:00:00Z");

    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);
    private final CollectInterval interval = mock(CollectInterval.class);
    private final MetricsQueryService service = new MetricsQueryService(mongoTemplate, interval);

    @Test
    void topics_joinsCurrentStateAndTotals_andKeepsTotalsOfGoneTopics() {
        when(mongoTemplate.find(any(Query.class), eq(TopicSnapshot.class), eq(Schema.SNAPSHOTS))).thenReturn(List.of(
                new TopicSnapshot("persistent://home/media/orders", "home", "media", "orders", "orders", true, NOW,
                        0, 0, 0, 0, 1, 0, 10, 0, 3, 30, 100, 1, 1, 0, null)));
        when(mongoTemplate.find(any(Query.class), eq(Document.class), eq(Schema.TOTALS))).thenReturn(List.of(
                new Document("_id", "persistent://home/media/orders").append("msgsIn", 500L),
                new Document("_id", "persistent://home/media/old").append(Schema.TENANT, "home")
                        .append(Schema.NAMESPACE, "media").append(Schema.BASE_NAME, "old").append("msgsIn", 7)));

        List<TopicStatsResponse> topics = service.topics("home", "media");

        assertThat(topics).extracting(TopicStatsResponse::name).containsExactly("old", "orders");
        assertThat(topics.get(0).current()).isNull();
        assertThat(topics.get(0).total().msgsIn()).isEqualTo(7);
        assertThat(topics.get(1).current().backlogMsgs()).isEqualTo(3);
        assertThat(topics.get(1).total().msgsIn()).isEqualTo(500);
    }

    @Test
    void topics_needsTheTenantOfANamespace() {
        assertThatThrownBy(() -> service.topics(null, "media")).isInstanceOf(InvalidQueryException.class);
    }

    @Test
    void collector_reportsTheNewestSampleAndTheInterval() {
        when(interval.seconds()).thenReturn(30);
        when(mongoTemplate.findOne(any(Query.class), eq(Document.class), eq(Schema.SNAPSHOTS)))
                .thenReturn(new Document(Schema.COLLECTED_AT, Date.from(NOW)));

        assertThat(service.collector().lastCollectionAt()).isEqualTo(NOW);
        assertThat(service.collector().intervalSeconds()).isEqualTo(30);
    }
}
