package com.catacumbas69.catavigia.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.catacumbas69.catavigia.checks.Check;
import com.catacumbas69.catavigia.checks.Finding;
import com.catacumbas69.catavigia.model.Alert;
import com.catacumbas69.catavigia.model.ArchivedAlert;
import com.catacumbas69.catavigia.model.Observation;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

class AlertEngineTest {

    private static final Instant ROUND = Instant.parse("2026-11-07T09:00:00Z");
    private static final String KEY = "bridge.failed|media-copy";

    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);
    private final AlertEvents events = mock(AlertEvents.class);
    private final AlertEngine engine = new AlertEngine(mongoTemplate, events,
            Clock.fixed(ROUND.plusSeconds(2), ZoneOffset.UTC));
    private final Check check = mock(Check.class);
    private final UpdateResult noneMatched = mock(UpdateResult.class);

    @BeforeEach
    void wire() {
        when(check.name()).thenReturn("bridges");
        when(check.types()).thenReturn(Set.of("bridge.failed", "bridge.blocked"));
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(Alert.class))).thenReturn(noneMatched);
        when(mongoTemplate.find(any(Query.class), eq(Observation.class))).thenReturn(List.of());
    }

    @Test
    void opensAnAlert_onlyOnceTheProblemWasSeenForTheDebounceRounds() {
        observationBecomes(new Observation(KEY, "bridge.failed", "media-copy", "bridges", 1, 0, ROUND));
        assertThat(engine.apply(check, ROUND, List.of(failed()), 2).opened()).isZero();
        verify(mongoTemplate, never()).insert(any(Alert.class));

        observationBecomes(new Observation(KEY, "bridge.failed", "media-copy", "bridges", 2, 0, ROUND));
        assertThat(engine.apply(check, ROUND, List.of(failed()), 2).opened()).isEqualTo(1);

        ArgumentCaptor<Alert> alert = ArgumentCaptor.forClass(Alert.class);
        verify(mongoTemplate).insert(alert.capture());
        assertThat(alert.getValue().key()).isEqualTo(KEY);
        assertThat(alert.getValue().severity()).isEqualTo("warning");
        verify(events).opened(alert.getValue());
    }

    @Test
    void publishesNothing_whenAnotherReplicaOpenedItFirst() {
        observationBecomes(new Observation(KEY, "bridge.failed", "media-copy", "bridges", 2, 0, ROUND));
        when(mongoTemplate.insert(any(Alert.class))).thenThrow(new DuplicateKeyException("open"));

        assertThat(engine.apply(check, ROUND, List.of(failed()), 2).opened()).isZero();

        verify(events, never()).opened(any());
    }

    @Test
    void changesNothing_forALateRound() {
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class),
                eq(Observation.class))).thenThrow(new DuplicateKeyException("newer round"));

        AlertEngine.Result result = engine.apply(check, ROUND, List.of(failed()), 1);

        assertThat(result.stale()).isEqualTo(1);
        verify(mongoTemplate, never()).insert(any(Alert.class));
        verify(mongoTemplate, never()).updateFirst(any(Query.class), any(Update.class), eq(Alert.class));
    }

    @Test
    void closesAndArchives_afterTheDebounceRoundsWithoutTheProblem() {
        Observation old = new Observation(KEY, "bridge.failed", "media-copy", "bridges", 0, 1, ROUND.minusSeconds(30));
        when(mongoTemplate.find(any(Query.class), eq(Observation.class))).thenReturn(List.of(old));
        observationBecomes(new Observation(KEY, "bridge.failed", "media-copy", "bridges", 0, 2, ROUND));
        Alert open = new Alert("id-1", KEY, "bridge.failed", "media-copy", "warning", Map.of(),
                ROUND.minusSeconds(600), ROUND.minusSeconds(60), null, null);
        when(mongoTemplate.findOne(any(Query.class), eq(Alert.class))).thenReturn(open);
        when(mongoTemplate.remove(any(Query.class), eq(Alert.class))).thenReturn(DeleteResult.acknowledged(1));

        assertThat(engine.apply(check, ROUND, List.of(), 2).closed()).isEqualTo(1);

        ArgumentCaptor<ArchivedAlert> archived = ArgumentCaptor.forClass(ArchivedAlert.class);
        verify(mongoTemplate).save(archived.capture());
        assertThat(archived.getValue().id()).isEqualTo("id-1");
        assertThat(archived.getValue().durationSeconds()).isEqualTo(602);
        verify(events).closed(archived.getValue());
        verify(mongoTemplate).remove(any(Query.class), eq(Observation.class));
    }

    @Test
    void keepsAnOpenAlertUpToDate_whileItIsStillSeen() {
        observationBecomes(new Observation(KEY, "bridge.failed", "media-copy", "bridges", 5, 0, ROUND));
        when(noneMatched.getMatchedCount()).thenReturn(1L);

        assertThat(engine.apply(check, ROUND, List.of(failed()), 2).opened()).isZero();

        verify(mongoTemplate, never()).insert(any(Alert.class));
    }

    private void observationBecomes(Observation observation) {
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class),
                eq(Observation.class))).thenReturn(observation);
    }

    private static Finding failed() {
        return new Finding("bridge.failed", "media-copy", Map.of("lastError", "boom"));
    }
}
