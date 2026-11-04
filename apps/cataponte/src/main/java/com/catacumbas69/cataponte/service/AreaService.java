package com.catacumbas69.cataponte.service;

import com.catacumbas69.cataponte.model.AreaDocument;
import com.catacumbas69.cataponte.model.BridgeDocument;
import com.catacumbas69.cataponte.repository.AreaRepository;
import com.catacumbas69.cataponte.repository.BridgeRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/**
 * Creates, changes and deletes areas (ADR 0014). Areas change definitions only: the workers are not affected.
 * Every change is published as an event by {@link AuditLog}, so it reaches Cataudit.
 */
@Service
public class AreaService {

    private final AreaRepository repository;
    private final BridgeRepository bridgeRepository;
    private final BridgeService bridges;
    private final AuditLog audit;

    /**
     * Creates the service.
     *
     * @param repository areas
     * @param bridgeRepository bridge definitions, to find the bridges of an area
     * @param bridges changes bridges (moves and deletions), auditing each one
     * @param audit audit log for configuration changes
     */
    public AreaService(AreaRepository repository, BridgeRepository bridgeRepository, BridgeService bridges,
                       AuditLog audit) {
        this.repository = repository;
        this.bridgeRepository = bridgeRepository;
        this.bridges = bridges;
        this.audit = audit;
    }

    /** What to do with the bridges of an area being deleted. */
    public enum BridgesOnDelete {
        /** Delete them. */
        DELETE,
        /** Move them to another area or to the general group. */
        MOVE
    }

    /**
     * Lists every area, sorted by name.
     *
     * @return all areas
     */
    public List<AreaDocument> findAll() {
        return repository.findAll(Sort.by("name"));
    }

    /**
     * Returns every area by id.
     *
     * @return the areas
     */
    public Map<String, AreaDocument> byId() {
        return repository.findAll().stream()
                .collect(Collectors.toMap(AreaDocument::getId, Function.identity(), (a, b) -> a, LinkedHashMap::new));
    }

    /**
     * Counts the bridges of every area.
     *
     * @return bridges per area id; areas without bridges are absent
     */
    public Map<String, Long> bridgeCounts() {
        return bridges.findAll().stream().filter(bridge -> bridge.getAreaId() != null)
                .collect(Collectors.groupingBy(BridgeDocument::getAreaId, Collectors.counting()));
    }

    /**
     * Returns one area.
     *
     * @param id area id
     * @return the area
     * @throws AreaNotFoundException if it does not exist
     */
    public AreaDocument get(String id) {
        return repository.findById(id).orElseThrow(() -> new AreaNotFoundException(id));
    }

    /**
     * Validates and stores a new area.
     *
     * @param draft the new area; id, version and timestamps are ignored
     * @param user client IP address, for the audit log
     * @return the stored area
     * @throws BridgeValidationException if a pattern is not valid
     * @throws BridgeConflictException if the name is taken
     */
    public AreaDocument create(AreaDocument draft, String user) {
        draft.setPatterns(AreaFilter.normalize(draft.getPatterns()));
        if (repository.existsByName(draft.getName())) {
            throw duplicateName(draft.getName());
        }
        draft.setId(null);
        draft.setVersion(null);
        draft.setCreatedAt(null);
        draft.setUpdatedAt(null);
        AreaDocument saved = save(draft);
        audit.areaChanged(user, AuditAction.CREATE_AREA, null, saved, null);
        return saved;
    }

    /**
     * Replaces an area's name, description and patterns. Always accepted: bridges whose topics no longer match
     * keep running and are reported outside the filter.
     *
     * @param id area id
     * @param changes the new definition
     * @param expectedVersion version the client edited; {@code null} skips the check
     * @param user client IP address, for the audit log
     * @return the stored area
     * @throws AreaNotFoundException if it does not exist
     * @throws BridgeConflictException if the version is stale or the name is taken
     * @throws BridgeValidationException if a pattern is not valid
     */
    public AreaDocument update(String id, AreaDocument changes, Long expectedVersion, String user) {
        AreaDocument existing = get(id);
        if (expectedVersion != null && !expectedVersion.equals(existing.getVersion())) {
            throw staleVersion();
        }
        List<String> patterns = AreaFilter.normalize(changes.getPatterns());
        if (repository.existsByNameAndIdNot(changes.getName(), id)) {
            throw duplicateName(changes.getName());
        }
        AreaDocument before = copyOf(existing);
        existing.setName(changes.getName());
        existing.setDescription(changes.getDescription());
        existing.setPatterns(patterns);
        AreaDocument saved = save(existing);
        audit.areaChanged(user, AuditAction.UPDATE_AREA, before, saved, null);
        return saved;
    }

    /**
     * Deletes an area. An area with bridges needs a choice: delete them, or move them to {@code moveTo} (another
     * area, or the general group when {@code null}). Each bridge deleted or moved is audited, then the area.
     *
     * @param id area id
     * @param onBridges what to do with its bridges; {@code null} only for an empty area
     * @param moveTo area to move them to, for {@link BridgesOnDelete#MOVE}; {@code null} for the general group
     * @param user client IP address, for the audit log
     * @throws AreaNotFoundException if the area does not exist
     * @throws BridgeConflictException if it has bridges and no choice was made
     * @throws BridgeValidationException if {@code moveTo} is the area itself or does not exist
     */
    public void delete(String id, BridgesOnDelete onBridges, String moveTo, String user) {
        AreaDocument area = get(id);
        List<BridgeDocument> members = bridgeRepository.findByAreaId(id);
        if (!members.isEmpty() && onBridges == null) {
            throw new BridgeConflictException("Area '" + area.getName() + "' has " + members.size()
                    + " bridge(s): choose bridges=delete, or bridges=move with an optional to=<area id>");
        }
        String target = onBridges == BridgesOnDelete.MOVE ? blankToNull(moveTo) : null;
        if (target != null && (Objects.equals(target, id) || !repository.existsById(target))) {
            throw new BridgeValidationException("to", "Cannot move the bridges to '" + target
                    + "': choose another existing area, or none for Bridges");
        }
        List<String> names = new ArrayList<>();
        for (BridgeDocument bridge : members) {
            if (onBridges == BridgesOnDelete.DELETE) {
                bridges.delete(bridge.getId(), user);
            } else {
                bridges.moveToArea(bridge.getId(), target, user);
            }
            names.add(bridge.getName());
        }
        repository.deleteById(id);
        Map<String, Object> details = new LinkedHashMap<>();
        if (!members.isEmpty()) {
            details.put("bridges", onBridges == BridgesOnDelete.DELETE ? "deleted" : "moved");
            details.put("bridgeNames", names);
            if (onBridges == BridgesOnDelete.MOVE) {
                details.put("movedTo", target);
            }
        }
        audit.areaChanged(user, AuditAction.DELETE_AREA, area, null, details.isEmpty() ? null : details);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static AreaDocument copyOf(AreaDocument area) {
        AreaDocument copy = new AreaDocument();
        copy.setId(area.getId());
        copy.setName(area.getName());
        copy.setDescription(area.getDescription());
        copy.setPatterns(area.getPatterns() == null ? List.of() : List.copyOf(area.getPatterns()));
        copy.setVersion(area.getVersion());
        return copy;
    }

    private AreaDocument save(AreaDocument area) {
        try {
            return repository.save(area);
        } catch (OptimisticLockingFailureException e) {
            throw staleVersion();
        } catch (DuplicateKeyException e) {
            throw duplicateName(area.getName());
        }
    }

    private static BridgeConflictException staleVersion() {
        return new BridgeConflictException("The area was changed by someone else; reload it and try again");
    }

    private static BridgeConflictException duplicateName(String name) {
        return new BridgeConflictException("An area named '" + name + "' already exists");
    }
}
