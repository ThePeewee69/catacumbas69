package com.catacumbas69.catapulsar.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.catacumbas69.catapulsar.model.AuditEntry;
import com.catacumbas69.catapulsar.repository.AuditRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

class AuditServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-30T21:00:00Z");

    private final AuditRepository repository = mock(AuditRepository.class);
    private final AuditService audit = new AuditService(repository, new DirectExecutor(),
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void recordMessage_storesEntryWithTimestampAndPayload() {
        audit.recordMessage("10.0.0.1", AuditAction.PRODUCE, "persistent://a/b/c", "{}", "k", Map.of("x", 1));

        ArgumentCaptor<AuditEntry> saved = ArgumentCaptor.forClass(AuditEntry.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getTimestamp()).isEqualTo(NOW);
        assertThat(saved.getValue().getAction()).isEqualTo("PRODUCE");
        assertThat(saved.getValue().getMessagePayload()).isEqualTo("{}");
        assertThat(saved.getValue().getMessageKey()).isEqualTo("k");
    }

    @Test
    void record_neverFails_whenMongoIsDown() {
        when(repository.save(any(AuditEntry.class))).thenThrow(new DataAccessResourceFailureException("down"));

        assertThatCode(() -> audit.record("10.0.0.1", AuditAction.DELETE_TOPIC, "persistent://a/b/c", null))
                .doesNotThrowAnyException();
    }

    @Test
    void record_neverFails_whenExecutorIsShutDown() {
        DirectExecutor executor = new DirectExecutor();
        executor.shutdown();
        AuditService closed = new AuditService(repository, executor, Clock.systemUTC());

        assertThatCode(() -> closed.record("10.0.0.1", AuditAction.CREATE_TOPIC, "t", null))
                .doesNotThrowAnyException();
    }

    /** Runs tasks on the calling thread; rejects them after shutdown like a real executor. */
    private static final class DirectExecutor extends AbstractExecutorService {

        private volatile boolean shutdown;

        @Override
        public void execute(Runnable command) {
            if (shutdown) {
                throw new java.util.concurrent.RejectedExecutionException("shut down");
            }
            command.run();
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    }
}
