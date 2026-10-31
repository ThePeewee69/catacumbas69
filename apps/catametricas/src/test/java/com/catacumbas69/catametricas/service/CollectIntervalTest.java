package com.catacumbas69.catametricas.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.catacumbas69.catametricas.TestProperties;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;

class CollectIntervalTest {

    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);
    private final CollectInterval interval = new CollectInterval(mongoTemplate, TestProperties.properties());

    @Test
    void usesTheValueSavedByCatapulsar() {
        saved("120");

        assertThat(interval.seconds()).isEqualTo(120);
    }

    @Test
    void fallsBackToTheDefault_whenMissingInvalidOrOutOfRange() {
        assertThat(interval.seconds()).isEqualTo(60);
        saved("abc");
        assertThat(interval.seconds()).isEqualTo(60);
        saved("5");
        assertThat(interval.seconds()).isEqualTo(60);
        saved("601");
        assertThat(interval.seconds()).isEqualTo(60);
    }

    @Test
    void fallsBackToTheDefault_whenMongoIsDown() {
        when(mongoTemplate.findById(eq(CollectInterval.SETTING_ID), eq(Document.class), eq(CollectInterval.COLLECTION)))
                .thenThrow(new DataAccessResourceFailureException("down"));

        assertThat(interval.seconds()).isEqualTo(60);
    }

    private void saved(String value) {
        when(mongoTemplate.findById(eq(CollectInterval.SETTING_ID), eq(Document.class), eq(CollectInterval.COLLECTION)))
                .thenReturn(new Document("_id", CollectInterval.SETTING_ID).append("value", value));
    }
}
