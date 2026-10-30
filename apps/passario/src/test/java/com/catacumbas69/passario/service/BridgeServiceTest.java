package com.catacumbas69.passario.service;

import static com.catacumbas69.passario.TestBridges.DEST_A;
import static com.catacumbas69.passario.TestBridges.DEST_B;
import static com.catacumbas69.passario.TestBridges.SOURCE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.catacumbas69.passario.TestBridges;
import com.catacumbas69.passario.model.BridgeDocument;
import com.catacumbas69.passario.repository.BridgeRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;

class BridgeServiceTest {

    private final BridgeRepository repository = mock(BridgeRepository.class);
    private final BridgeService service = new BridgeService(repository);

    @BeforeEach
    void saveReturnsArgument() {
        when(repository.save(any(BridgeDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void create_storesDeduplicatedDestinations_andIgnoresClientIdentity() {
        BridgeDocument draft = TestBridges.unordered("client-id", DEST_A, DEST_B, DEST_A);
        draft.setVersion(42L);

        BridgeDocument saved = service.create(draft);

        assertThat(saved.getId()).isNull();
        assertThat(saved.getVersion()).isNull();
        assertThat(saved.resolveDestinationTopics()).containsExactly(DEST_A, DEST_B);
    }

    @Test
    void create_rejectsSourceAsDestination() {
        BridgeDocument draft = TestBridges.unordered("1", DEST_A, SOURCE);

        assertThatThrownBy(() -> service.create(draft))
                .isInstanceOf(BridgeValidationException.class)
                .extracting("field").isEqualTo("destinations");
        verify(repository, never()).save(any());
    }

    @Test
    void create_rejectsOrderedBridgeOnSharedSubscription() {
        BridgeDocument draft = TestBridges.unordered("1", DEST_A);
        draft.setOrdered(true);

        assertThatThrownBy(() -> service.create(draft))
                .isInstanceOf(BridgeValidationException.class)
                .extracting("field").isEqualTo("ordered");
    }

    @Test
    void create_rejectsBridgeWithoutDestinations() {
        BridgeDocument draft = TestBridges.unordered("1");

        assertThatThrownBy(() -> service.create(draft)).isInstanceOf(BridgeValidationException.class);
    }

    @Test
    void create_rejectsDuplicateName() {
        when(repository.existsByName("bridge-1")).thenReturn(true);

        assertThatThrownBy(() -> service.create(TestBridges.unordered("1", DEST_A)))
                .isInstanceOf(BridgeConflictException.class);
    }

    @Test
    void create_mapsUniqueIndexViolation_toConflict() {
        when(repository.save(any(BridgeDocument.class))).thenThrow(new DuplicateKeyException("dup"));

        assertThatThrownBy(() -> service.create(TestBridges.unordered("1", DEST_A)))
                .isInstanceOf(BridgeConflictException.class);
    }

    @Test
    void update_rejectsStaleVersion() {
        BridgeDocument stored = TestBridges.unordered("1", DEST_A);
        stored.setVersion(3L);
        when(repository.findById("1")).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> service.update("1", TestBridges.unordered("1", DEST_B), 2L))
                .isInstanceOf(BridgeConflictException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void update_mapsConcurrentWrite_toConflict() {
        when(repository.findById("1")).thenReturn(Optional.of(TestBridges.unordered("1", DEST_A)));
        when(repository.save(any(BridgeDocument.class))).thenThrow(new OptimisticLockingFailureException("race"));

        assertThatThrownBy(() -> service.update("1", TestBridges.unordered("1", DEST_B), null))
                .isInstanceOf(BridgeConflictException.class);
    }

    @Test
    void update_replacesDefinition_keepingIdentity() {
        BridgeDocument stored = TestBridges.unordered("1", DEST_A);
        when(repository.findById("1")).thenReturn(Optional.of(stored));
        BridgeDocument changes = TestBridges.ordered("ignored", DEST_B);

        BridgeDocument saved = service.update("1", changes, 1L);

        assertThat(saved.getId()).isEqualTo("1");
        assertThat(saved.resolveDestinationTopics()).containsExactly(DEST_B);
        assertThat(saved.isOrderedEffective()).isTrue();
    }

    @Test
    void update_failsForUnknownBridge() {
        when(repository.findById("x")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update("x", TestBridges.unordered("x", DEST_A), null))
                .isInstanceOf(BridgeNotFoundException.class);
    }

    @Test
    void setEnabled_isIdempotent() {
        BridgeDocument stored = TestBridges.unordered("1", DEST_A);
        when(repository.findById("1")).thenReturn(Optional.of(stored));

        service.setEnabled("1", true);

        verify(repository, never()).save(any());
    }

    @Test
    void setEnabled_storesNewState() {
        BridgeDocument stored = TestBridges.unordered("1", DEST_A);
        when(repository.findById("1")).thenReturn(Optional.of(stored));

        BridgeDocument saved = service.setEnabled("1", false);

        assertThat(saved.isEnabled()).isFalse();
        verify(repository).save(stored);
    }

    @Test
    void delete_failsForUnknownBridge() {
        when(repository.existsById("x")).thenReturn(false);

        assertThatThrownBy(() -> service.delete("x")).isInstanceOf(BridgeNotFoundException.class);
        verify(repository, never()).deleteById(any());
    }
}
