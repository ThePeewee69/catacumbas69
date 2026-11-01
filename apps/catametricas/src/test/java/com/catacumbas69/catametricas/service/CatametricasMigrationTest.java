package com.catacumbas69.catametricas.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mongodb.MongoNamespace;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import java.util.Set;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

class CatametricasMigrationTest {

    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);
    private final MongoDatabase database = mock(MongoDatabase.class);
    private final CatametricasMigration migration = new CatametricasMigration(mongoTemplate);

    @BeforeEach
    void wire() {
        when(mongoTemplate.getDb()).thenReturn(database);
        when(database.getName()).thenReturn("catacumbas69");
    }

    @Test
    void renamesEveryOldCollection_andDropsTheLeaseAndStatus() {
        when(mongoTemplate.getCollectionNames()).thenReturn(Set.of("contario_topic_snapshots", "contario_topic_minutes",
                "contario_topic_hours", "contario_topic_totals", "contario_locks", "contario_status", "bridges"));
        MongoCollection<Document> minutes = collection("contario_topic_minutes");
        collection("contario_topic_snapshots");
        collection("contario_topic_hours");
        collection("contario_topic_totals");

        assertThat(migration.migrate()).isEqualTo(4);

        verify(minutes).renameCollection(new MongoNamespace("catacumbas69", "catametricas_topic_minutes"));
        verify(mongoTemplate).dropCollection("contario_locks");
        verify(mongoTemplate).dropCollection("contario_status");
        verify(mongoTemplate, never()).dropCollection("bridges");
    }

    @Test
    void leavesTheOldCollectionAlone_whenTheNewOneAlreadyExists() {
        when(mongoTemplate.getCollectionNames())
                .thenReturn(Set.of("contario_topic_totals", "catametricas_topic_totals"));
        MongoCollection<Document> totals = collection("contario_topic_totals");

        assertThat(migration.migrate()).isZero();

        verify(totals, never()).renameCollection(any(MongoNamespace.class));
    }

    @Test
    void doesNothing_onceMigrated() {
        when(mongoTemplate.getCollectionNames()).thenReturn(Set.of("catametricas_topic_totals"));

        assertThat(migration.migrate()).isZero();

        verify(mongoTemplate, never()).dropCollection(any(String.class));
    }

    @SuppressWarnings("unchecked")
    private MongoCollection<Document> collection(String name) {
        MongoCollection<Document> collection = mock(MongoCollection.class);
        when(mongoTemplate.getCollection(name)).thenReturn(collection);
        return collection;
    }
}
