package com.catacumbas69.cataudit.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;

class AuditStoreTest {

    @Test
    void filter_withoutValues_matchesEverything() {
        Document query = AuditStore.filter(new AuditQuery(null, " ", null, null, null, null, null, 0, 50))
                .getQueryObject();

        assertThat(query).isEmpty();
    }

    @Test
    void filter_combinesEveryValue() {
        Document query = AuditStore.filter(new AuditQuery("catapulsar", "topic", "created", "persistent://a/",
                "10.0.0.1", Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-11-01T00:00:00Z"), 0, 50))
                .getQueryObject();

        // Instants are converted by Spring Data when the query runs, so compare the documents, not JSON.
        List<Document> parts = query.getList("$and", Document.class);
        assertThat(parts).contains(new Document("app", "catapulsar"), new Document("entity", "topic"),
                new Document("action", "created"), new Document("actor", "10.0.0.1"),
                new Document("time", new Document("$gte", Instant.parse("2026-10-01T00:00:00Z"))
                        .append("$lt", Instant.parse("2026-11-01T00:00:00Z"))));
        assertThat(parts.toString()).contains("subject", "persistent://a/");
    }
}
