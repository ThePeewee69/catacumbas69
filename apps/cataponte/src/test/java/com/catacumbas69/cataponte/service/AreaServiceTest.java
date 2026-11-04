package com.catacumbas69.cataponte.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.catacumbas69.cataponte.TestBridges;
import com.catacumbas69.cataponte.model.AreaDocument;
import com.catacumbas69.cataponte.model.BridgeDocument;
import com.catacumbas69.cataponte.repository.AreaRepository;
import com.catacumbas69.cataponte.repository.BridgeRepository;
import com.catacumbas69.cataponte.service.AreaService.BridgesOnDelete;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class AreaServiceTest {

    private static final String USER = "10.0.0.1";

    private final AreaRepository repository = mock(AreaRepository.class);
    private final BridgeRepository bridgeRepository = mock(BridgeRepository.class);
    private final BridgeService bridges = mock(BridgeService.class);
    private final AuditLog audit = mock(AuditLog.class);
    private final AreaService service = new AreaService(repository, bridgeRepository, bridges, audit);

    @BeforeEach
    void saveReturnsArgument() {
        when(repository.save(any(AreaDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void create_normalizesThePatterns_andAudits() {
        AreaDocument saved = service.create(area(null, " home/media/* ", "home/media/*"), USER);

        assertThat(saved.getPatterns()).containsExactly("home/media/*");
        verify(audit).areaChanged(eq(USER), eq(AuditAction.CREATE_AREA), isNull(), eq(saved), isNull());
    }

    @Test
    void create_refusesATakenName() {
        when(repository.existsByName("media")).thenReturn(true);

        assertThatThrownBy(() -> service.create(area(null), USER)).isInstanceOf(BridgeConflictException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void update_isAlwaysAccepted_andAuditsBeforeAndAfter() {
        AreaDocument existing = area("a1", "home/**");
        existing.setVersion(3L);
        when(repository.findById("a1")).thenReturn(Optional.of(existing));

        AreaDocument saved = service.update("a1", area(null, "home/media/*"), 3L, USER);

        assertThat(saved.getPatterns()).containsExactly("home/media/*");
        verify(audit).areaChanged(eq(USER), eq(AuditAction.UPDATE_AREA),
                argThat(before -> before.getPatterns().equals(List.of("home/**"))), eq(saved), isNull());
    }

    @Test
    void update_refusesAStaleVersion() {
        AreaDocument existing = area("a1");
        existing.setVersion(3L);
        when(repository.findById("a1")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.update("a1", area(null), 2L, USER))
                .isInstanceOf(BridgeConflictException.class);
    }

    @Test
    void delete_anAreaWithBridges_needsAChoice() {
        when(repository.findById("a1")).thenReturn(Optional.of(area("a1")));
        when(bridgeRepository.findByAreaId("a1")).thenReturn(List.of(TestBridges.unordered("b1", TestBridges.DEST_A)));

        assertThatThrownBy(() -> service.delete("a1", null, null, USER))
                .isInstanceOf(BridgeConflictException.class).hasMessageContaining("1 bridge(s)");
        verify(repository, never()).deleteById(any());
    }

    @Test
    void delete_withDelete_deletesEveryBridge_thenTheArea() {
        when(repository.findById("a1")).thenReturn(Optional.of(area("a1")));
        when(bridgeRepository.findByAreaId("a1")).thenReturn(List.of(
                TestBridges.unordered("b1", TestBridges.DEST_A), TestBridges.unordered("b2", TestBridges.DEST_A)));

        service.delete("a1", BridgesOnDelete.DELETE, null, USER);

        InOrder order = inOrder(bridges, repository, audit);
        order.verify(bridges).delete("b1", USER);
        order.verify(bridges).delete("b2", USER);
        order.verify(repository).deleteById("a1");
        order.verify(audit).areaChanged(eq(USER), eq(AuditAction.DELETE_AREA), any(), isNull(),
                eq(Map.of("bridges", "deleted", "bridgeNames", List.of("bridge-b1", "bridge-b2"))));
    }

    @Test
    void delete_withMove_movesTheBridgesToTheOtherArea() {
        when(repository.findById("a1")).thenReturn(Optional.of(area("a1")));
        when(repository.existsById("a2")).thenReturn(true);
        BridgeDocument bridge = TestBridges.unordered("b1", TestBridges.DEST_A);
        when(bridgeRepository.findByAreaId("a1")).thenReturn(List.of(bridge));

        service.delete("a1", BridgesOnDelete.MOVE, "a2", USER);

        verify(bridges).moveToArea("b1", "a2", USER);
        verify(bridges, never()).delete(any(), any());
        verify(repository).deleteById("a1");
    }

    @Test
    void delete_withMoveAndNoTarget_movesTheBridgesToTheGeneralGroup() {
        when(repository.findById("a1")).thenReturn(Optional.of(area("a1")));
        when(bridgeRepository.findByAreaId("a1")).thenReturn(List.of(TestBridges.unordered("b1", TestBridges.DEST_A)));

        service.delete("a1", BridgesOnDelete.MOVE, " ", USER);

        verify(bridges).moveToArea("b1", null, USER);
    }

    @Test
    void delete_refusesToMoveToItselfOrAnUnknownArea() {
        when(repository.findById("a1")).thenReturn(Optional.of(area("a1")));
        when(repository.existsById("a1")).thenReturn(true);
        when(bridgeRepository.findByAreaId("a1")).thenReturn(List.of(TestBridges.unordered("b1", TestBridges.DEST_A)));

        assertThatThrownBy(() -> service.delete("a1", BridgesOnDelete.MOVE, "a1", USER))
                .isInstanceOf(BridgeValidationException.class);
        assertThatThrownBy(() -> service.delete("a1", BridgesOnDelete.MOVE, "gone", USER))
                .isInstanceOf(BridgeValidationException.class);
        verify(bridges, never()).moveToArea(any(), any(), any());
        verify(repository, never()).deleteById(any());
    }

    @Test
    void delete_anEmptyArea_needsNoChoice() {
        AreaDocument area = area("a1");
        when(repository.findById("a1")).thenReturn(Optional.of(area));

        service.delete("a1", null, null, USER);

        verify(repository).deleteById("a1");
        verify(audit).areaChanged(USER, AuditAction.DELETE_AREA, area, null, null);
    }

    @Test
    void bridgeCounts_countsOnlyBridgesWithAnArea() {
        BridgeDocument inArea = TestBridges.unordered("b1", TestBridges.DEST_A);
        inArea.setAreaId("a1");
        when(bridges.findAll()).thenReturn(List.of(inArea, TestBridges.unordered("b2", TestBridges.DEST_A)));

        assertThat(service.bridgeCounts()).isEqualTo(Map.of("a1", 1L));
    }

    private static AreaDocument area(String id, String... patterns) {
        AreaDocument area = new AreaDocument();
        area.setId(id);
        area.setName("media");
        area.setPatterns(List.of(patterns));
        return area;
    }
}
