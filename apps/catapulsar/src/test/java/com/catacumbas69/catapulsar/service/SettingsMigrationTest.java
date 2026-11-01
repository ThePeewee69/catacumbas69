package com.catacumbas69.catapulsar.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.catacumbas69.catapulsar.model.Setting;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

class SettingsMigrationTest {

    private static final Instant THEN = Instant.parse("2026-10-30T10:00:00Z");

    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final SettingsMigration migration = new SettingsMigration(mongo);

    @Test
    void doesNothing_whenTheOldCollectionIsGone() {
        assertThat(migration.migrate()).isZero();

        verify(mongo, never()).dropCollection(any(String.class));
    }

    @Test
    void copiesWithTheNewIds_thenDropsTheOldCollection() {
        when(mongo.collectionExists("pulsario_settings")).thenReturn(true);
        when(mongo.findAll(Setting.class, "pulsario_settings")).thenReturn(List.of(
                new Setting("passario.url", "http://cataponte-dev:8082", THEN, "10.0.0.1"),
                new Setting("contario.collect-interval-seconds", "30", THEN, "10.0.0.2")));
        when(mongo.findById(eq(Setting.CATAPONTE_URL), eq(Setting.class)))
                .thenReturn(null, new Setting(Setting.CATAPONTE_URL, "x", THEN, "y"));
        when(mongo.findById(eq(Setting.COLLECT_INTERVAL_SECONDS), eq(Setting.class)))
                .thenReturn(null, new Setting(Setting.COLLECT_INTERVAL_SECONDS, "30", THEN, "y"));

        assertThat(migration.migrate()).isEqualTo(2);

        verify(mongo).insert(argThat((Setting s) -> s.getId().equals("catapulsar.cataponte-url")
                && s.getValue().equals("http://cataponte-dev:8082") && s.getUpdatedBy().equals("10.0.0.1")));
        verify(mongo).insert(argThat((Setting s) -> s.getId().equals("catametricas.collect-interval-seconds")));
        verify(mongo).dropCollection("pulsario_settings");
    }

    @Test
    void keepsANewerValue_alreadyInTheNewCollection() {
        when(mongo.collectionExists("pulsario_settings")).thenReturn(true);
        when(mongo.findAll(Setting.class, "pulsario_settings")).thenReturn(List.of(
                new Setting("ui.refresh-interval-seconds", "5", THEN, "10.0.0.1")));
        when(mongo.findById(eq(Setting.REFRESH_INTERVAL_SECONDS), eq(Setting.class)))
                .thenReturn(new Setting(Setting.REFRESH_INTERVAL_SECONDS, "20", Instant.now(), "10.0.0.9"));

        assertThat(migration.migrate()).isZero();

        verify(mongo, never()).insert(any(Setting.class));
        verify(mongo).dropCollection("pulsario_settings");
    }

    @Test
    void keepsTheOldCollection_whenItHoldsAnUnknownSetting() {
        when(mongo.collectionExists("pulsario_settings")).thenReturn(true);
        when(mongo.findAll(Setting.class, "pulsario_settings")).thenReturn(List.of(
                new Setting("something.else", "1", THEN, "10.0.0.1")));

        migration.migrate();

        verify(mongo, never()).dropCollection(any(String.class));
    }
}
