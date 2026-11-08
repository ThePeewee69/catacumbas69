package com.catacumbas69.catavigia.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.catacumbas69.catacommons.consumers.InstanceId;
import com.catacumbas69.catacommons.settings.SharedSettings;
import com.catacumbas69.catavigia.checks.Check;
import com.catacumbas69.catavigia.checks.Finding;
import com.catacumbas69.catavigia.checks.SourceUnavailableException;
import com.catacumbas69.catavigia.model.CheckTask;
import com.catacumbas69.catavigia.model.TaskRun;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

class CheckRunnerTest {

    private static final Instant ROUND = Instant.parse("2026-11-07T09:00:00Z");

    private final Check check = mock(Check.class);
    private final AlertEngine engine = mock(AlertEngine.class);
    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);
    private CheckRunner runner;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void wire() {
        when(check.name()).thenReturn("bridges");
        ObjectProvider<SharedSettings> settings = mock(ObjectProvider.class);
        InstanceId instance = mock(InstanceId.class);
        when(instance.id()).thenReturn("pod-1");
        when(engine.apply(any(), any(), any(), anyInt())).thenReturn(new AlertEngine.Result(0, 0, 0, 0));
        runner = new CheckRunner(List.of(check), engine, mongoTemplate, mock(CatavigiaIndexes.class), settings,
                instance, Clock.fixed(ROUND, ZoneOffset.UTC));
    }

    @Test
    void runsTheCheck_andGivesWhatItSawToTheEngine_withTheDefaultDebounce() throws Exception {
        List<Finding> findings = List.of(new Finding("bridge.failed", "a", null));
        when(check.run()).thenReturn(findings);

        assertThat(runner.handle(CheckTask.of("bridges", ROUND, 30))).isEqualTo(CheckRunner.Outcome.DONE);

        verify(engine).apply(check, ROUND, findings, 2);
    }

    @Test
    void skipsATaskAnotherReplicaClaimed() throws Exception {
        when(mongoTemplate.insert(any(TaskRun.class))).thenThrow(new DuplicateKeyException("claimed"));

        assertThat(runner.handle(CheckTask.of("bridges", ROUND, 30))).isEqualTo(CheckRunner.Outcome.DUPLICATE);

        verify(check, never()).run();
    }

    @Test
    void retries_andReleasesTheClaim_whenTheSourceDoesNotAnswer() throws Exception {
        when(check.run()).thenThrow(new SourceUnavailableException("Cataponte down", null));

        assertThat(runner.handle(CheckTask.of("bridges", ROUND, 30))).isEqualTo(CheckRunner.Outcome.RETRY);

        verify(mongoTemplate).remove(any(Query.class), eq(TaskRun.class));
        verify(engine, never()).apply(any(), any(), any(), anyInt());
    }

    @Test
    void refusesUnknownChecksAndBrokenTasks() {
        assertThat(runner.handle(CheckTask.of("nope", ROUND, 30))).isEqualTo(CheckRunner.Outcome.INVALID);
        assertThat(runner.handle(new CheckTask("x", "bridges", "not a time", 30)))
                .isEqualTo(CheckRunner.Outcome.INVALID);
        assertThat(runner.handle(null)).isEqualTo(CheckRunner.Outcome.INVALID);
    }
}
